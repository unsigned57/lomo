use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail};
use tree_sitter::{Node, Parser, Tree};

/// Domain `UseCases` that are intentionally uncalled by production UI, background worker,
/// or caller pipelines.
///
/// Under the First-Principles Gate, this must remain empty: every `*UseCase` declared
/// under `apps/android/domain/src` must either be actively wired to production pipelines
/// or deleted under tail-deletion. Merely declaring or registering a `UseCase` in DI glue
/// code does not satisfy reachability, and neither does a non-binding mention
/// (`UseCase::class`/callable references, `typealias` declarations, comments, or the
/// declaration's own file).
const UNREACHABLE_USECASE_ALLOWLIST: &[(&str, &str)] = &[];

/// Directories excluded from scanning for production callers. `di` is a caller-side
/// exclusion only: a `*UseCase` declared inside `di/` still owes reachability, so the
/// declaration inventory below skips only test source roots.
const EXCLUDED_DIR_NAMES: &[&str] = &["test", "androidTest", "di"];
/// Directories excluded from the `*UseCase` declaration inventory.
const DECL_EXCLUDED_DIR_NAMES: &[&str] = &["test", "androidTest"];
const USECASE_DIRECTORY: &str = "apps/android/domain/src/usecase";
const DOMAIN_SOURCE_DIRECTORY: &str = "apps/android/domain/src";

/// Representation of a declared domain `UseCase`.
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct UseCaseDecl {
    pub name: String,
    pub rel_file: String,
    pub line: usize,
    /// The declaring file's `package` header — needed to tell a qualified
    /// `com.lomo.domain.usecase.X` tail (binds this usecase) from
    /// `other.package.X` (a different package's same-named type).
    pub package_name: String,
}

/// Verifies that every declared domain use case is reachable from a production entry point.
///
/// # Errors
///
/// Returns an error for missing sources, unreadable or unparseable files, or unreachable
/// use cases.
pub fn check_usecase_reachability(root: &Path) -> Result<()> {
    let usecase_dir = root.join(USECASE_DIRECTORY);
    if !usecase_dir.is_dir() {
        bail!(
            "missing domain usecase directory: {}",
            usecase_dir.display()
        );
    }

    let domain_src = root.join(DOMAIN_SOURCE_DIRECTORY);
    if !domain_src.is_dir() {
        bail!("missing domain source directory: {}", domain_src.display());
    }

    let usecases = collect_usecases(root, &domain_src)?;
    if usecases.is_empty() {
        bail!("no UseCases discovered in {}", domain_src.display());
    }

    let production_sources = collect_production_sources(root, &usecases)?;
    let (direct_roots, dependencies) = analyze_call_graph(&usecases, &production_sources);
    let reachable = compute_transitive_reachability(&direct_roots, &dependencies);

    let allowlist_map: BTreeMap<&str, &str> =
        UNREACHABLE_USECASE_ALLOWLIST.iter().copied().collect();
    let mut violations = Vec::new();

    // Stale allowlist check (Tail deletion invariant)
    let usecase_names: BTreeSet<&str> = usecases.iter().map(|u| u.name.as_str()).collect();
    for allowlisted_name in allowlist_map.keys() {
        if !usecase_names.contains(*allowlisted_name) {
            violations.push(format!(
                "UseCase `{allowlisted_name}` in UNREACHABLE_USECASE_ALLOWLIST is not declared in domain/src/usecase. Remove stale allowlist entry"
            ));
        } else if reachable.contains(*allowlisted_name) {
            violations.push(format!(
                "UseCase `{allowlisted_name}` in UNREACHABLE_USECASE_ALLOWLIST is now reachable in production. Remove it from the allowlist (tail deletion)"
            ));
        }
    }

    // Reachability enforcement
    for usecase in &usecases {
        let is_reachable = reachable.contains(usecase.name.as_str());
        let is_allowlisted = allowlist_map.contains_key(usecase.name.as_str());

        if !is_reachable && !is_allowlisted {
            violations.push(format!(
                "Domain UseCase `{}` declared in `{}:{}` has 0 production consumers in app/data pipelines (only DI/tests, or entirely uncalled). Connect it to an active ViewModel/Worker pipeline, or delete it under First-Principles tail deletion.",
                usecase.name, usecase.rel_file, usecase.line
            ));
        }
    }

    if !violations.is_empty() {
        use std::fmt::Write as _;
        let mut message = String::from("UseCase Reachability check failed with violations:\n");
        for v in violations {
            writeln!(message, "  - {v}")?;
        }
        bail!("{message}");
    }

    crate::util::emit_stderr(format_args!(
        "xtask: UseCase reachability verified ({} UseCases scanned, {} external roots, {} reachable, {} allowlisted)",
        usecases.len(),
        direct_roots.len(),
        reachable.len(),
        UNREACHABLE_USECASE_ALLOWLIST.len()
    ));

    Ok(())
}

/// Collects every `*UseCase` declaration under `apps/android/domain/src`, recursively.
/// A declaration hidden in a subdirectory or moved outside `usecase/` still owes
/// reachability. Declaration discovery is AST-based: a commented-out or string-embedded
/// `class XUseCase` is not a `class_declaration` node and cannot mint a phantom
/// obligation. Files that cannot possibly declare a usecase (no `UseCase` text at all)
/// are skipped before parsing — a file's name text is a strict superset of any
/// `*UseCase` declaration it could hold.
fn collect_usecases(root: &Path, domain_src: &Path) -> Result<Vec<UseCaseDecl>> {
    let mut files = Vec::new();
    collect_decl_kotlin_files(domain_src, &mut files)?;
    files.sort();

    let mut usecases = Vec::new();
    for path in files {
        let rel_file = path
            .strip_prefix(root)
            .unwrap_or(&path)
            .to_string_lossy()
            .to_string();

        let content = fs::read_to_string(&path)?;
        if !content.contains("UseCase") {
            continue;
        }
        let tree = parse_kotlin(&content, &rel_file)?;
        let package_name = file_package(&tree, &content);
        for (name, line) in declared_type_names(&tree, &content) {
            if name.ends_with("UseCase") {
                usecases.push(UseCaseDecl {
                    name,
                    rel_file: rel_file.clone(),
                    line,
                    package_name: package_name.clone(),
                });
            }
        }
    }

    usecases.sort();
    Ok(usecases)
}

fn collect_decl_kotlin_files(dir: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if DECL_EXCLUDED_DIR_NAMES.contains(&name) || name.starts_with('.') {
                continue;
            }
            collect_decl_kotlin_files(&path, files)?;
        } else if path.is_file() && path.extension().is_some_and(|ext| ext == "kt") {
            files.push(path);
        }
    }
    Ok(())
}

/// Every named type declaration (`class`/`interface`/`object`/`companion object`) with
/// its declared name and 1-based line number. Names are read from the grammar's `name`
/// field, so modifiers, annotations, split headers and backtick-escaped names all
/// resolve structurally; anonymous `object : Super()` expressions carry no `name`
/// field and mint no obligation.
fn declared_type_names(tree: &Tree, source: &str) -> Vec<(String, usize)> {
    let mut names = Vec::new();
    collect_named_types(tree.root_node(), source, &mut names);
    names
}

fn collect_named_types(node: Node, source: &str, names: &mut Vec<(String, usize)>) {
    if matches!(
        node.kind(),
        "class_declaration" | "object_declaration" | "companion_object"
    ) && let Some(name) = node.child_by_field_name("name")
    {
        names.push((
            ident_text(name, source).to_owned(),
            name.start_position().row + 1,
        ));
    }
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        collect_named_types(child, source, names);
    }
}

struct ParsedSource {
    rel_path: String,
    source: String,
    tree: Tree,
    scope: FileScope,
}

fn collect_production_sources(root: &Path, usecases: &[UseCaseDecl]) -> Result<Vec<ParsedSource>> {
    let scan_roots = [
        root.join("apps/android/app/src"),
        root.join("apps/android/data/src"),
        root.join("apps/android/domain/src"),
        root.join("apps/android/ui-components/src"),
    ];

    let mut kt_files = Vec::new();
    for root in &scan_roots {
        collect_production_kotlin_files(root, &mut kt_files)?;
    }
    kt_files.sort();

    // A file can only bind a usecase whose name text appears in it: every binding
    // channel — bare name (import/same-package/wildcard), qualified tail, or alias
    // target — spells the declared identifier verbatim somewhere in the file.
    // Files that cannot bind any usecase are skipped before parsing; whether their
    // syntax would parse is then irrelevant to reachability.
    let usecase_names: Vec<&str> = usecases.iter().map(|uc| uc.name.as_str()).collect();

    let mut parsed_sources = Vec::new();
    for file in &kt_files {
        let content = fs::read_to_string(file)?;
        if !usecase_names.iter().any(|name| content.contains(name)) {
            continue;
        }
        let rel_path = file
            .strip_prefix(root)
            .unwrap_or(file)
            .to_string_lossy()
            .to_string();
        let tree = parse_kotlin(&content, &rel_path)?;
        let scope = analyze_file_scope(&tree, &content);
        parsed_sources.push(ParsedSource {
            rel_path,
            source: content,
            tree,
            scope,
        });
    }

    Ok(parsed_sources)
}

fn collect_production_kotlin_files(dir: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
    if !dir.is_dir() {
        return Ok(());
    }
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if EXCLUDED_DIR_NAMES.contains(&name) || name.starts_with('.') {
                continue;
            }
            collect_production_kotlin_files(&path, files)?;
        } else if path.is_file() && path.extension().is_some_and(|ext| ext == "kt") {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if !name.ends_with("Module.kt") {
                files.push(path);
            }
        }
    }
    Ok(())
}

/// `(direct_roots, dependencies)`: usecases consumed by non-domain production sources,
/// and the usecase→usecase edges between declaring domain files.
type UseCaseGraph<'a> = (BTreeSet<&'a str>, BTreeMap<&'a str, BTreeSet<&'a str>>);

/// Reachability semantics: a usecase is consumed when a production source outside
/// `apps/android/domain/src` binds it (call sites, constructor-injected type positions,
/// member access), or when a *declaring* domain file references it — the usecase graph
/// itself. Domain sources that declare no usecase cannot prove pipeline reachability on
/// their own, so a mention inside them is ignored rather than treated as a consumer
/// (documented approximation; usecase→usecase edges still reach transitively).
fn analyze_call_graph<'a>(
    usecases: &'a [UseCaseDecl],
    sources: &'a [ParsedSource],
) -> UseCaseGraph<'a> {
    let mut direct_roots = BTreeSet::new();
    let mut dependencies: BTreeMap<&'a str, BTreeSet<&'a str>> = BTreeMap::new();

    for uc in usecases {
        dependencies.entry(uc.name.as_str()).or_default();
    }

    // Which usecases each declaration file owns. A file that declares none has no graph
    // identity to hang a dependency edge on.
    let mut decls_by_file: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for uc in usecases {
        decls_by_file
            .entry(uc.rel_file.as_str())
            .or_default()
            .push(uc.name.as_str());
    }

    for uc in usecases {
        for src in sources {
            // A declaration file is never its own consumer, wherever it lives.
            if src.rel_path == uc.rel_file {
                continue;
            }
            if !contains_identifier_usage(src, &uc.name, &uc.package_name) {
                continue;
            }

            if !src.rel_path.starts_with(DOMAIN_SOURCE_DIRECTORY) {
                // External production consumer found!
                direct_roots.insert(uc.name.as_str());
            } else if let Some(declaring) = decls_by_file.get(src.rel_path.as_str()) {
                // Usage between usecase declarations anywhere under domain/src.
                for other_uc in declaring {
                    dependencies
                        .entry(*other_uc)
                        .or_default()
                        .insert(uc.name.as_str());
                }
            }
        }
    }

    (direct_roots, dependencies)
}

fn compute_transitive_reachability<'a>(
    direct_roots: &BTreeSet<&'a str>,
    dependencies: &BTreeMap<&'a str, BTreeSet<&'a str>>,
) -> BTreeSet<&'a str> {
    let mut reachable = direct_roots.clone();
    let mut queue: Vec<&'a str> = direct_roots.iter().copied().collect();

    while let Some(current) = queue.pop() {
        if let Some(callees) = dependencies.get(current) {
            for callee in callees {
                if reachable.insert(callee) {
                    queue.push(callee);
                }
            }
        }
    }

    reachable
}

// ---------------------------------------------------------------------------
// Kotlin AST analysis (tree-sitter-kotlin-ng)
// ---------------------------------------------------------------------------

/// Parses one Kotlin source file with the pinned tree-sitter grammar.
///
/// The gate fails closed on any grammar error — a file whose syntax cannot be
/// accounted for may hide a real consumer, so it is rejected rather than
/// approximated. One structural hazard survives a clean parse: a `{`-body
/// that follows a `by`-delegation can be captured as the delegation's
/// trailing lambda. For `enum class` that lambda is recovered as the enum
/// body (its entry names are declared, see `enum_captured_body`); an `enum`
/// modifier with no `enum_class_body` child and no recoverable capture is an
/// unaccountable declaration shape and is rejected as well.
fn parse_kotlin(source: &str, rel_path: &str) -> Result<Tree> {
    let mut parser = Parser::new();
    parser
        .set_language(&tree_sitter_kotlin_ng::LANGUAGE.into())
        .with_context(|| format!("{rel_path}: cannot initialize Kotlin parser"))?;
    let tree = parser
        .parse(source, None)
        .with_context(|| format!("{rel_path}: Kotlin parser produced no tree"))?;
    if tree.root_node().has_error() {
        bail!("{rel_path}: Kotlin parse error — cannot account for this file's syntax");
    }
    if let Some(bad) = enum_without_body(tree.root_node()) {
        bail!(
            "{rel_path}:{}: `enum class` without a visible enum-class body — \
             cannot account for this declaration shape",
            bad.start_position().row + 1
        );
    }
    Ok(tree)
}

/// Finds a `class_declaration` that carries the `enum` modifier but no
/// `enum_class_body` child and no recoverable captured body — a header that
/// never opened its body is a declaration shape the gate cannot account for.
fn enum_without_body(node: Node) -> Option<Node> {
    if node.kind() == "class_declaration"
        && declares_enum(node)
        && !node
            .children(&mut node.walk())
            .any(|child| child.kind() == "enum_class_body")
        && enum_captured_body(node).is_none()
    {
        return Some(node);
    }
    let mut cursor = node.walk();
    node.children(&mut cursor).find_map(enum_without_body)
}

/// Recovers the enum body the grammar captures as a trailing lambda: in
/// `enum class E : I by d { entries }` Kotlin itself reads `{ entries }` as
/// the class body, but this grammar nests it under the delegation
/// expression as `by d({ entries })`. The captured body is always the last
/// `lambda_literal` in *trailing* position under `delegation_specifiers` —
/// the last child of a `call_expression`, possibly wrapped in
/// `annotated_lambda`. Lambdas inside argument lists, indexers, parens or
/// infix operands (`by f({x})`, `by a + {d}`) are never last children of a
/// call, so they cannot masquerade as the body — only a real trailing
/// lambda qualifies, which is exactly where the enum body lands.
fn enum_captured_body(class_node: Node<'_>) -> Option<Node<'_>> {
    let delegation = class_node
        .children(&mut class_node.walk())
        .find(|child| child.kind() == "delegation_specifiers")?;
    let mut captured: Option<Node> = None;
    collect_trailing_lambdas(delegation, &mut captured);
    captured
}

fn collect_trailing_lambdas<'a>(node: Node<'a>, last: &mut Option<Node<'a>>) {
    if node.kind() == "lambda_literal" {
        let holder = node
            .parent()
            .filter(|p| p.kind() == "annotated_lambda")
            .unwrap_or(node);
        if holder.parent().is_some_and(|p| {
            p.kind() == "call_expression" && p.child(p.child_count() - 1) == Some(holder)
        }) {
            *last = Some(node);
        }
    }
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        collect_trailing_lambdas(child, last);
    }
}

/// The declared name of one enum entry inside a captured lambda body:
/// `DeadUseCase` → `identifier`, `A("x")` → the call's callee identifier,
/// `@Anno A("x")` → past the `modifiers`/`annotation` wrapper. The leading
/// comma-separated children up to the first `;` are the entry list — a
/// `function_declaration` or deeper payload is never an entry name.
fn enum_entry_name(node: Node) -> Option<Node> {
    match node.kind() {
        "identifier" => Some(node),
        "call_expression" => node
            .children(&mut node.walk())
            .find(|child| child.kind() == "identifier"),
        "annotated_expression" | "annotated_lambda" => node
            .children(&mut node.walk())
            .find(|child| {
                !matches!(
                    child.kind(),
                    "modifiers" | "annotation" | "unescaped_annotation"
                )
            })
            .and_then(enum_entry_name),
        _ => None,
    }
}

/// Mirrors the enum entry list the captured lambda stands in for: children
/// up to the first `;` are entries (`A`, `B(1)`, `@Anno C`); whatever follows
/// the `;` — or sits deeper inside an entry's own payload — is member
/// syntax already covered by the generic declaration walk.
fn collect_captured_entries(body: Node, source: &str, locals: &mut BTreeSet<String>) {
    for child in body.children(&mut body.walk()) {
        if child.kind() == ";" {
            break;
        }
        if let Some(name) = enum_entry_name(child) {
            locals.insert(ident_text(name, source).to_owned());
        }
    }
}

/// Whether a `class_declaration` carries the `enum` modifier (`enum class E`).
fn declares_enum(node: Node) -> bool {
    node.children(&mut node.walk())
        .filter(|child| child.kind() == "modifiers")
        .flat_map(|mods| mods.children(&mut mods.walk()).collect::<Vec<_>>())
        .any(|modifier| {
            modifier.kind() == "class_modifier"
                && modifier
                    .children(&mut modifier.walk())
                    .any(|child| child.kind() == "enum")
        })
}

/// The name text an `identifier` node carries: `` `backtick` `` escapes are
/// syntax, not part of the binding name.
fn ident_text<'a>(node: Node<'a>, source: &'a str) -> &'a str {
    let raw = node.utf8_text(source.as_bytes()).unwrap_or("");
    raw.strip_prefix('`')
        .and_then(|stripped| stripped.strip_suffix('`'))
        .unwrap_or(raw)
}

/// The dotted segments of a `qualified_identifier` — `com.lomo.usecase` →
/// `["com", "lomo", "usecase"]`; escaped segments lose their backticks.
fn qualified_segments(node: Node, source: &str) -> Vec<String> {
    node.children(&mut node.walk())
        .filter(|child| child.kind() == "identifier")
        .map(|child| ident_text(child, source).to_owned())
        .collect()
}

/// The file's `package` header — `""` when absent. An unnamed package can never
/// match a usecase's declared package, which is the strict direction.
fn file_package(tree: &Tree, source: &str) -> String {
    tree.root_node()
        .children(&mut tree.root_node().walk())
        .find(|child| child.kind() == "package_header")
        .and_then(|header| {
            header
                .children(&mut header.walk())
                .find(|child| child.kind() == "qualified_identifier")
        })
        .map_or_else(String::new, |name| {
            qualified_segments(name, source).join(".")
        })
}

/// One file's binding environment for usecase-name resolution. Bare-name
/// resolution follows Kotlin's own order: an explicitly imported same-named
/// type wins over the file's package and any wildcard, so `import
/// com.other.DeadUseCase` makes *every* bare `DeadUseCase` bind `com.other.*`.
#[derive(Default)]
struct FileScope {
    /// The file's `package` header ("" when absent — a named package can never
    /// resolve it bare, which is the strict direction).
    package: String,
    /// `(introduced name, target)` per `import`: `import a.b.C` →
    /// `("C","a.b.C")`, `import a.b.C as D` → `("D","a.b.C")`,
    /// `import a.b.*` → `("*","a.b")`.
    imports: Vec<(String, String)>,
    /// Identifiers bound anywhere in the file's declaration positions —
    /// *anonymous* ones (value/lambda parameter names including modifier-,
    /// annotation- and use-site-annotation-prefixed slots plus the untyped
    /// `set(X)`/`set(X,)` setter parameter, `for`/`when`-subject loop
    /// variables, destructured components, declared type parameters, `catch`
    /// parameters) and *named* declarations alike: `val`/`var` locals and
    /// member/constructor properties, `fun` functions,
    /// `class`/`interface`/`object`/`typealias`/`enum` names and `enum` entry
    /// names. A reuse binds the local declaration, never the domain type —
    /// the set is file-wide (a name shadowed anywhere in the file excludes
    /// all bare occurrences), which over-reports obligations rather than
    /// silently passing: the strict direction.
    locals: BTreeSet<String>,
}

/// Builds the file's binding environment from the parse tree: `package`/`import`
/// directives from the root children, declared names from every declaration
/// node's name position.
fn analyze_file_scope(tree: &Tree, source: &str) -> FileScope {
    let mut scope = FileScope {
        package: file_package(tree, source),
        ..FileScope::default()
    };
    for child in tree.root_node().children(&mut tree.root_node().walk()) {
        if child.kind() == "import" {
            record_import(child, source, &mut scope.imports);
        }
    }
    collect_local_names(tree.root_node(), source, &mut scope.locals);
    scope
}

/// Records one `import` directive: `import a.b.C` → `("C","a.b.C")`,
/// `import a.b.C as D` → `("D","a.b.C")`, `import a.b.*` → `("*","a.b")`. The
/// alias keyword is the `as` *token* — `` `as` `` backticked inside a path is a
/// segment — which the grammar separates structurally.
fn record_import(node: Node, source: &str, imports: &mut Vec<(String, String)>) {
    let mut path: Option<String> = None;
    let mut alias: Option<String> = None;
    let mut wildcard = false;
    for child in node.children(&mut node.walk()) {
        match child.kind() {
            "qualified_identifier" => path = Some(qualified_segments(child, source).join(".")),
            "identifier" => alias = Some(ident_text(child, source).to_owned()),
            "*" => wildcard = true,
            _ => {}
        }
    }
    let Some(path) = path else {
        return;
    };
    if wildcard {
        imports.push(("*".to_owned(), path));
    } else if let Some(alias) = alias {
        imports.push((alias, path));
    } else {
        let name = path.rsplit('.').next().unwrap_or(&path).to_owned();
        imports.push((name, path));
    }
}

/// Folds every name a declaration introduces into `locals`. Declaration names
/// are read from the grammar's own name positions — the `name` field of
/// `class`/`object`/`companion object`/`fun` declarations, and the first
/// `identifier` child of parameter/variable/enum-entry/type-parameter/setter/
/// catch nodes — so annotations, modifiers, use-site targets and receivers
/// around the name can never keep it out of scope. `typealias` names bind the
/// file but the right-hand side is scanned by the use side (it never counts
/// as a consumer).
fn collect_local_names(node: Node, source: &str, locals: &mut BTreeSet<String>) {
    match node.kind() {
        "package_header" | "import" | "label" => return,
        "type_alias" => {
            if let Some(name) = first_identifier_child(node) {
                locals.insert(ident_text(name, source).to_owned());
            }
            return;
        }
        "class_declaration"
        | "object_declaration"
        | "companion_object"
        | "function_declaration" => {
            if let Some(name) = node.child_by_field_name("name") {
                locals.insert(ident_text(name, source).to_owned());
            }
            // An enum whose `enum_class_body` was captured as the
            // delegation's trailing lambda still declares its entry names
            // in scope — they shadow bare occurrences inside the body the
            // same way a real `enum_class_body`'s entries do.
            if node.kind() == "class_declaration"
                && declares_enum(node)
                && !node
                    .children(&mut node.walk())
                    .any(|child| child.kind() == "enum_class_body")
                && let Some(body) = enum_captured_body(node)
            {
                collect_captured_entries(body, source, locals);
            }
        }
        "variable_declaration"
        | "parameter"
        | "class_parameter"
        | "enum_entry"
        | "type_parameter"
        | "setter"
        | "catch_block"
        | "lambda_parameters" => {
            if let Some(name) = first_identifier_child(node) {
                locals.insert(ident_text(name, source).to_owned());
            }
        }
        _ => {}
    }
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        collect_local_names(child, source, locals);
    }
}

/// The first `identifier` directly inside `node` — the declared-name slot of a
/// parameter/variable/entry node. Annotation and modifier payloads nest under
/// their own children, so they can never surface here as the name.
fn first_identifier_child(node: Node) -> Option<Node> {
    node.children(&mut node.walk())
        .find(|child| child.kind() == "identifier")
}

/// Whether a bare `ident` in `scope` resolves to the usecase `package`.`ident`
/// — Kotlin's bare-name order: an explicitly imported same-named type shadows
/// package and wildcard resolution (`import other.X` makes every bare `X` bind
/// `other.*`, and an `as` alias of another name shadows the domain name too),
/// then the file's own package, then an unambiguous wildcard import.
fn bare_name_binds(scope: &FileScope, ident: &str, package: &str) -> bool {
    let usecase_fqn = format!("{package}.{ident}");
    let mut shadowed = false;
    let mut explicit = false;
    let mut usecase_wildcard = false;
    let mut other_wildcard = false;
    for (name, target) in &scope.imports {
        if name == "*" {
            if target == package {
                usecase_wildcard = true;
            } else {
                other_wildcard = true;
            }
        } else if name == ident {
            if target == &usecase_fqn {
                explicit = true;
            } else {
                shadowed = true;
            }
        }
    }
    !shadowed && (explicit || scope.package == package || (usecase_wildcard && !other_wildcard))
}

/// The terminal (leaf) node immediately before `node` in tree order, ascending
/// past ancestors when `node` is a first child — `-> X`, `= X` and `, X` all
/// expose the preceding punctuation the same way the token stream did.
fn prev_terminal(mut node: Node) -> Option<Node> {
    loop {
        if let Some(sibling) = node.prev_sibling() {
            let mut leaf = sibling;
            while leaf.child_count() > 0 {
                leaf = leaf.child(leaf.child_count() - 1)?;
            }
            return Some(leaf);
        }
        node = node.parent()?;
    }
}

/// The terminal (leaf) node immediately after `node` in tree order.
fn next_terminal(mut node: Node) -> Option<Node> {
    loop {
        if let Some(sibling) = node.next_sibling() {
            let mut leaf = sibling;
            while leaf.child_count() > 0 {
                leaf = leaf.child(0)?;
            }
            return Some(leaf);
        }
        node = node.parent()?;
    }
}

/// Flattens a `navigation_expression` chain into its elements: the receiver at
/// index 0 followed by each member, with the operator (`.`, `?.`, `::`) that
/// precedes it. `a.b.c` parses left-associatively, so the outermost node's
/// left child is itself a navigation expression.
fn navigation_elements(node: Node<'_>) -> Vec<(Node<'_>, String)> {
    debug_assert_eq!(node.kind(), "navigation_expression");
    let mut elements = Vec::new();
    let mut cursor = node.walk();
    let mut children = node.children(&mut cursor);
    let (Some(left), Some(op), Some(right)) = (children.next(), children.next(), children.next())
    else {
        return elements;
    };
    if left.kind() == "navigation_expression" {
        elements = navigation_elements(left);
    } else {
        elements.push((left, String::new()));
    }
    elements.push((right, op.kind().to_owned()));
    elements
}

/// Whether the `identifier` at segment index `tail` of a qualified chain binds
/// the usecase: the dotted prefix must spell the usecase's `package` exactly
/// (`com.lomo.domain.usecase.X` binds; `com.other.X` names a foreign package,
/// `value.X` member access) and the chain must be rooted at a *bare*
/// identifier nothing in this file binds — Kotlin resolves `com` in the
/// package namespace only when no `val com`/`import a.B as com`/wildcard
/// captures it first (a captured root makes every `.segment` a member read).
fn qualified_tail_binds(
    elements: &[(Node, String)],
    tail: usize,
    package: &str,
    scope: &FileScope,
    source: &str,
) -> bool {
    // `.`-joined segments only: `?.`/`::` read members, never packages; the
    // receiver must itself be a bare identifier (a call/index/literal result
    // anchors a member chain, not a package path).
    let Some(&(root, _)) = elements.first() else {
        return false;
    };
    if root.kind() != "identifier"
        || elements
            .iter()
            .take(tail + 1)
            .skip(1)
            .any(|(elem, op)| op != "." || elem.kind() != "identifier")
    {
        return false;
    }
    let prefix: Vec<&str> = elements
        .iter()
        .take(tail)
        .map(|(elem, _)| ident_text(*elem, source))
        .collect();
    if prefix.join(".") != package {
        return false;
    }
    let root_name = ident_text(root, source);
    !scope.locals.contains(root_name)
        && !scope.imports.iter().any(|(name, _)| name == root_name)
        // A `*` entry is an unbounded introduced-name set: it may contain a
        // `root` binding this file cannot disprove, so the tail is not
        // provably package-rooted and stays suppressed.
        && !scope.imports.iter().any(|(name, _)| name == "*")
}

/// Whether the `identifier` node `ident` — whose text already matched the
/// usecase name — is a *binding* occurrence in this file. Non-binding
/// positions mirror the grammar: member/reference segments (`a.X`, `X::y`,
/// `x::X`), qualified tails from a foreign or captured package, declaration
/// name slots (the name is in `locals`), label references (`X@`, `goto@X` —
/// `label` sibling adjacency), named-argument labels (`f(X = 1)`), infix
/// function names (`a X b`), `when`-entry value conditions (`X ->`), and the
/// strict boundary on a bare `-> X` lambda/branch payload that does not call,
/// navigate or specialize. Everything else needs Kotlin's bare-name channel
/// (explicit import, same package, or unambiguous wildcard).
fn identifier_binds(node: Node, source: &str, scope: &FileScope, package: &str) -> bool {
    let name = ident_text(node, source);
    if scope.locals.contains(name) {
        return false;
    }
    let Some(parent) = node.parent() else {
        return bare_name_binds(scope, name, package);
    };
    match parent.kind() {
        "navigation_expression" => {
            // Find the outermost chain this occurrence rides on.
            let mut top = parent;
            while let Some(grand) = top.parent() {
                if grand.kind() != "navigation_expression" {
                    break;
                }
                let at_end = grand.child(0) == Some(top)
                    || grand.child(grand.child_count() - 1) == Some(top);
                if !at_end {
                    break;
                }
                top = grand;
            }
            let elements = navigation_elements(top);
            let Some(index) = elements.iter().position(|(elem, _)| *elem == node) else {
                return false;
            };
            if index == 0 {
                // The chain's receiver: `X::member` evaluates to a reference,
                // `X.member` resolves `X` bare.
                if elements.get(1).is_some_and(|(_, op)| op == "::") {
                    return false;
                }
                return bare_name_binds(scope, name, package);
            }
            // A member segment: `::` binds a reference name, `?.` a nullable
            // member; only a `.`-tail can spell the usecase's package.
            if elements.get(index).is_none_or(|elem| elem.1 != ".") {
                return false;
            }
            // `pkg.X::class` — the `::` after the tail still reads a reference.
            if elements.get(index + 1).is_some_and(|(_, op)| op == "::") {
                return false;
            }
            qualified_tail_binds(&elements, index, package, scope, source)
        }
        "user_type" => {
            // Qualified type tails (`com.pkg.X`) bind like navigation tails;
            // the first segment is the type's bare root (`X`, `X.Inner`).
            let segments: Vec<(Node, String)> = parent
                .children(&mut parent.walk())
                .filter(|child| child.kind() == "identifier")
                .map(|child| (child, ".".to_owned()))
                .collect();
            let Some(position) = segments.iter().position(|(elem, _)| *elem == node) else {
                return false;
            };
            if position == 0 {
                return bare_name_binds(scope, name, package);
            }
            qualified_tail_binds(&segments, position, package, scope, source)
        }
        // The infix name position of `a X b` resolves in the function
        // namespace — it can never name a class.
        "infix_expression" if parent.named_child(1) == Some(node) => false,
        // A named-argument label `f(X = v)` names the parameter, not a type.
        "value_argument" if node.next_sibling().is_some_and(|s| s.kind() == "=") => false,
        // An `identifier` inside a `qualified_identifier` outside the preamble
        // is header data, not an expression.
        "qualified_identifier" => false,
        _ => {
            // `X@` defines a label, `break@X`/`return@X` target one — label
            // adjacency is spelled by `label`/`@` siblings, never a use.
            if node
                .prev_sibling()
                .is_some_and(|s| matches!(s.kind(), "label" | "@"))
                || node
                    .next_sibling()
                    .is_some_and(|s| matches!(s.kind(), "label" | "@"))
            {
                return false;
            }
            // `X ->` — a `when`-entry value condition — reads `X` in the value
            // namespace; it can never name the class.
            if next_terminal(node).is_some_and(|t| t.kind() == "->") {
                return false;
            }
            // `-> X`: a bare identifier opening a branch/lambda payload is a
            // strict-boundary ambiguity — it may read a shadowed value, so it
            // only binds when it visibly calls or specializes (`X()`, `X<>`).
            if prev_terminal(node).is_some_and(|t| t.kind() == "->")
                && !next_terminal(node).is_some_and(|t| matches!(t.kind(), "(" | "<"))
            {
                return false;
            }
            bare_name_binds(scope, name, package)
        }
    }
}

/// Descends an `annotation` node counting only its *argument* payload. The
/// annotation's own type name (`@get:X`, `@com.pkg.X`, `@X(...)`) resolves in
/// the annotation namespace and can never name a usecase, while argument
/// expressions are real code: `@Anno(DeadUseCase())` constructs.
fn visit_annotation(
    node: Node,
    source: &str,
    scope: &FileScope,
    ident: &str,
    package: &str,
) -> bool {
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        match child.kind() {
            "user_type" => {
                if type_arguments_bind(child, source, scope, ident, package) {
                    return true;
                }
            }
            "constructor_invocation" => {
                if annotation_arguments_bind(child, source, scope, ident, package) {
                    return true;
                }
            }
            _ => {
                if visit_node(child, source, scope, ident, package) {
                    return true;
                }
            }
        }
    }
    false
}

/// Whether any `type_arguments` child of `node` holds a binding occurrence:
/// `@Anno<X>` writes the usecase inside the annotation's generic argument —
/// real code that requires the type.
fn type_arguments_bind(
    node: Node,
    source: &str,
    scope: &FileScope,
    ident: &str,
    package: &str,
) -> bool {
    node.children(&mut node.walk())
        .filter(|child| child.kind() == "type_arguments")
        .any(|args| visit_node(args, source, scope, ident, package))
}

/// Whether a `constructor_invocation` under an `annotation` holds a binding
/// occurrence in its argument payload — `@Anno(DeadUseCase())` constructs,
/// while the annotation's own `user_type` name never reaches the walker.
fn annotation_arguments_bind(
    invocation: Node,
    source: &str,
    scope: &FileScope,
    ident: &str,
    package: &str,
) -> bool {
    invocation
        .children(&mut invocation.walk())
        .any(|part| match part.kind() {
            "value_arguments" | "type_arguments" => visit_node(part, source, scope, ident, package),
            "user_type" => type_arguments_bind(part, source, scope, ident, package),
            _ => false,
        })
}

/// Recursive use-site walk: returns true when any `identifier` node named
/// `ident` sits in a binding position. Subtrees that can never hold a binding
/// occurrence are skipped whole: the `package`/`import` preamble (header data,
/// never code), `typealias` payloads (a non-binding declaration), callable
/// references (`X::f` reads a `KFunction`), labels and use-site targets
/// (`@setparam:`), and annotation names.
fn visit_node(node: Node, source: &str, scope: &FileScope, ident: &str, package: &str) -> bool {
    match node.kind() {
        "package_header" | "import" | "type_alias" | "callable_reference" | "label"
        | "use_site_target" => return false,
        "annotation" | "unescaped_annotation" => {
            return visit_annotation(node, source, scope, ident, package);
        }
        "identifier" => {
            if ident_text(node, source) == ident {
                return identifier_binds(node, source, scope, package);
            }
            return false;
        }
        _ => {}
    }
    let mut cursor = node.walk();
    node.children(&mut cursor)
        .any(|child| visit_node(child, source, scope, ident, package))
}

/// Whether `ident` is *bound* by this source — i.e. the occurrence can create a
/// runtime dependency. Constructor-injection conventions mean a type position
/// (`param: XUseCase`) is a real consumer, but references that evaluate to a
/// value without binding a `XUseCase` are not: `XUseCase::class`/callable
/// references, `typealias` declarations, `import`/`package` headers, and a
/// declaration's own name. Inside `<>` generic arguments the occurrence still
/// counts — the field type honestly requires the usecase to exist.
///
/// Same-named *declaration positions* do not bind either: `val`/`var`/`fun`
/// names, `class`/`object`/`interface`/`typealias`/`enum` headers, `X@`/
/// `break@X` labels, `{ X -> }` lambda parameters, a `qualifier.X` tail whose
/// package is not the usecase's declared package, function/constructor
/// parameter names (modifier-, annotation- and use-site-annotation-prefixed
/// slots and the untyped `set(X)` setter parameter included), `for`/`when`
/// subject variables, destructured components, `enum` entries, declared type
/// parameters, named-argument labels (`g(X = 1)`), and `$X` template
/// shorthands (a `$name` read resolves in Kotlin's property namespace — it
/// parses as `string_content`, never an `identifier`). Locally declared names
/// also shadow every later bare reuse — a lambda parameter's own body uses,
/// a member `val X`, a nested `class X`, an `enum` entry's bare use inside
/// the enum body.
///
/// A *bare* occurrence additionally has to resolve to this usecase at all —
/// Kotlin requires a binding channel: an `import` of `package.ident`, a
/// wildcard `import package.*` (unambiguous, i.e. no other wildcard could
/// supply the name), or the file's own package being `package`. An
/// `import other.ident` — or an `as` alias of another name — shadows the
/// bare name for the whole file, and an explicit same-named import wins over
/// same-package resolution, so both directions stay strict.
///
/// The approximation direction is *strict*: an ambiguous occurrence — a bare
/// identifier right after `->` without a following `(` or `<` (possible
/// shadow parameter vs type position), or an imported-but-same-named
/// other-package type used unqualified — is treated as non-binding, which
/// can only over-report obligations, never silently pass.
fn contains_identifier_usage(source: &ParsedSource, ident: &str, package: &str) -> bool {
    visit_node(
        source.tree.root_node(),
        &source.source,
        &source.scope,
        ident,
        package,
    )
}
