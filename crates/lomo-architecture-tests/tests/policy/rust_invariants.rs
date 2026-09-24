//! Audit-derived source invariants (audit invariants I1/I2/I5/I7). Every rule is a
//! first-principles ownership law, not a pattern blacklist:
//!
//! - `rust-checked-wire` (I7): public wire-shaped types (`*Request`, `*Command`, `*Intent`,
//!   `*Envelope`) that derive `Deserialize` admit unchecked construction. They must name a
//!   checked entry (`try_from`/`from`/`remote`) or stop deriving `Deserialize`.
//! - `rust-identity-sentinel` (I1): identity fields (generation, epoch, fence, stamp,
//!   validator, `revision_token`, operation/cycle/draft/artifact/lease/session ids, ...)
//!   cannot be minted from literals, `Uuid::new_v4`, hashes, wall clocks or
//!   `Default::default()` outside an owning constructor (`fn new`/`initial`/`default`/
//!   `empty`/`zero`/`begin`/`restored`).
//! - `rust-error-sniff` (I2): failure disposition must travel as typed `code`/`category`;
//!   branching on `error.to_string()` text reintroduces message coupling.
//! - `rust-bounded-io` (I5): boundary crates prove a byte budget before materializing
//!   bytes; `fs::read`/`read_to_end`/`read_to_string` outside a `read_bounded` owner or a
//!   `bounded-io-ok` behavior contract is rejected.
//! - `rust-loop-boundary-io` (I5): remote object transport calls cannot hide inside loops
//!   or collection iteration in the sync/git/lan boundary crates (N+1 amplification).

use proc_macro2::Span;
use syn::spanned::Spanned;
use syn::visit::{self, Visit};
use syn::{
    BinOp, Expr, ExprBinary, ExprCall, ExprMatch, ExprMethodCall, Item, ItemFn, Lit, Local, Member,
    Meta, Pat, Path,
};

use super::Violation;

const IDENTITY_SUFFIXES: &[&str] = &[
    "generation",
    "epoch",
    "fence",
    "stamp",
    "validator",
    "revision_token",
    "operation_id",
    "cycle_id",
    "activation_id",
    "draft_id",
    "artifact_id",
    "lease_id",
    "occurrence_id",
    "reminder_id",
    "capture_id",
    "batch_id",
    "session_id",
    "correlation_id",
];

const CONSTRUCTOR_NAMES: &[&str] = &[
    "new",
    "initial",
    "default",
    "empty",
    "zero",
    "begin",
    "restored",
    "placeholder",
    "seed",
    "bootstrap",
    "absent",
    "none",
    "missing",
    "connect",
    "open",
    "create",
    "build",
    "start",
    "launch",
];

const BOUNDARY_IO_CRATES: &[&str] = &["lomo-sync", "lomo-git", "lomo-lan", "lomo-media"];

const LOOP_IO_CRATES: &[&str] = &["lomo-sync", "lomo-git", "lomo-lan"];

const TRANSPORT_CALLEES: &[&str] = &[
    "get_to_temp",
    "put_from_file",
    "get_object",
    "put_object",
    "delete_object",
    "head_object",
    "list_objects",
    "load_object",
    "load_bytes",
    "fetch_object",
    "send_request",
    "round_trip",
    "request_page",
    "download_object",
    "upload_object",
    "observe_validator",
];

const ITERATOR_ADAPTERS: &[&str] = &[
    "map",
    "map_ok",
    "and_then",
    "then",
    "for_each",
    "try_for_each",
    "filter_map",
    "inspect",
    "flat_map",
    "map_while",
    "scan",
    "fold",
    "try_fold",
    "for_each_concurrent",
];

/// Types/namespaces whose constructors mint fresh values rather than own facts.
const MINTING_ROOTS: &[&str] = &[
    "Uuid",
    "Default",
    "Vec",
    "String",
    "HashMap",
    "BTreeMap",
    "HashSet",
    "BTreeSet",
    "VecDeque",
    "Sha",
    "Sha256",
    "Sha512",
    "Sha1",
    "Blake2",
    "Blake3",
    "Md5",
    "sha1",
    "sha2",
    "sha256",
    "sha512",
    "blake2",
    "blake3",
    "md5",
    "rand",
    "fastrand",
    "thread_rng",
    "OsRng",
    "SystemTime",
    "Instant",
    "Utc",
    "Local",
    "DateTime",
];

const MINTING_METHODS: &[&str] = &[
    "new",
    "new_v4",
    "new_v7",
    "now_v7",
    "nil",
    "default",
    "digest",
    "hash",
    "of_slice",
    "of_bytes",
    "now",
    "thread_rng",
    "random",
    "gen_range",
    "v4",
];

const BOUNDED_IO_MARKER: &str = "bounded-io-ok";
const LOOP_IO_MARKER: &str = "loop-io-ok";
const IDENTITY_MINT_MARKER: &str = "identity-mint-ok";

pub fn rust_invariant_violations(path: &str, source: &str) -> Result<Vec<Violation>, String> {
    let file =
        syn::parse_file(source).map_err(|error| format!("{path}: invalid Rust syntax: {error}"))?;
    let mut visitor = InvariantPolicy {
        path,
        lines: source.lines().map(str::to_owned).collect(),
        boundary_io: crate_scoped(path, BOUNDARY_IO_CRATES),
        loop_io: crate_scoped(path, LOOP_IO_CRATES),
        fn_stack: Vec::new(),
        loop_depth: 0,
        violations: Vec::new(),
    };
    visitor.visit_file(&file);
    Ok(visitor.violations)
}

fn crate_scoped(path: &str, crates: &[&str]) -> bool {
    let Some(rest) = path.split("crates/").nth(1) else {
        return false;
    };
    let Some(name) = rest.split('/').next() else {
        return false;
    };
    crates.contains(&name) && rest.contains("/src/")
}

fn is_identity_name(name: &str) -> bool {
    let name = name.trim_start_matches("r#");
    IDENTITY_SUFFIXES
        .iter()
        .any(|suffix| name == *suffix || name.ends_with(&format!("_{suffix}")))
}

fn is_constructor_name(name: &str) -> bool {
    let name = name.trim_start_matches("r#");
    CONSTRUCTOR_NAMES.iter().any(|candidate| {
        name == *candidate
            || name.starts_with(&format!("{candidate}_"))
            || name.ends_with(&format!("_{candidate}"))
    })
}

fn is_errish_ident(name: &str) -> bool {
    let name = name.trim_start_matches("r#");
    matches!(
        name,
        "e" | "err" | "error" | "failure" | "cause" | "reason" | "exception"
    ) || name.ends_with("_err")
        || name.ends_with("_error")
        || name.ends_with("_failure")
}

fn path_segments(path: &Path) -> Vec<String> {
    path.segments
        .iter()
        .map(|segment| segment.ident.to_string())
        .collect()
}

fn last_segment(expr: &Expr) -> Option<String> {
    let Expr::Path(expr) = expr else { return None };
    expr.path
        .segments
        .last()
        .map(|segment| segment.ident.to_string())
}

/// `Ident` receiver names that count as error materialization when stringified.
fn is_errish_receiver(expr: &Expr) -> bool {
    if let Expr::Path(path) = expr {
        return path
            .path
            .segments
            .last()
            .is_some_and(|segment| is_errish_ident(&segment.ident.to_string()));
    }
    if let Expr::Field(field) = expr {
        return match &field.member {
            Member::Named(name) => is_errish_ident(&name.to_string()),
            Member::Unnamed(_) => false,
        };
    }
    false
}

/// Whether `expr` renders error text at a *decision position*: the expression itself
/// (through `&`, parens and string adapters like `.as_str()`/`.trim()`) reduces to
/// `ERRISH.to_string()`/`to_owned()`. Error-mapping closures (`map_err(|e| ...)`) are
/// diagnostics, not branching, so they are never at decision position.
fn error_text_at_decision_position(expr: &Expr) -> bool {
    if let Expr::MethodCall(call) = expr {
        if matches!(call.method.to_string().as_str(), "to_string" | "to_owned")
            && is_errish_receiver(&call.receiver)
        {
            return true;
        }
        // Walk only the receiver chain — `x.to_string().as_str()` still bottoms out
        // at the error; arguments and closures are not decision positions.
        return error_text_at_decision_position(&call.receiver);
    }
    if let Expr::Reference(inner) = expr {
        return error_text_at_decision_position(&inner.expr);
    }
    if let Expr::Paren(inner) = expr {
        return error_text_at_decision_position(&inner.expr);
    }
    if let Expr::Call(call) = expr {
        return error_text_at_decision_position(&call.func);
    }
    false
}

/// Identity fields may not be minted from literals, UUIDs, hashes, wall clocks or
/// `Default::default()` — those are forgeries, not owner-issued facts.
fn is_forbidden_identity_rhs(expr: &Expr) -> bool {
    if let Expr::Lit(lit) = expr {
        return match &lit.lit {
            Lit::Int(_) | Lit::Float(_) => true,
            Lit::Str(text) => text.value().is_empty(),
            Lit::ByteStr(_)
            | Lit::CStr(_)
            | Lit::Byte(_)
            | Lit::Char(_)
            | Lit::Bool(_)
            | Lit::Verbatim(_)
            | _ => false,
        };
    }
    if let Expr::Call(call) = expr {
        return call_mints_value(call);
    }
    if let Expr::MethodCall(call) = expr {
        return is_forbidden_identity_rhs(&call.receiver)
            || matches!(
                call.method.to_string().as_str(),
                "digest" | "finalize" | "gen" | "gen_range" | "sample" | "random"
            );
    }
    if let Expr::Macro(mac) = expr {
        return mac
            .mac
            .path
            .segments
            .last()
            .is_some_and(|segment| matches!(segment.ident.to_string().as_str(), "format" | "vec"));
    }
    if let Expr::Path(path) = expr {
        return path
            .path
            .segments
            .last()
            .is_some_and(|segment| segment.ident == "nil");
    }
    if let Expr::Reference(inner) = expr {
        return is_forbidden_identity_rhs(&inner.expr);
    }
    if let Expr::Paren(inner) = expr {
        return is_forbidden_identity_rhs(&inner.expr);
    }
    if let Expr::Try(inner) = expr {
        return is_forbidden_identity_rhs(&inner.expr);
    }
    if let Expr::Cast(inner) = expr {
        return is_forbidden_identity_rhs(&inner.expr);
    }
    if let Expr::Unary(inner) = expr {
        return is_forbidden_identity_rhs(&inner.expr);
    }
    if let Expr::Await(inner) = expr {
        return is_forbidden_identity_rhs(&inner.base);
    }
    false
}

/// A free-standing call mints identity when its path is rooted at a minting type/namespace
/// and the tail method is a minting method — or when any path segment is an RNG root
/// (`fastrand::u64(..)` mints regardless of tail name).
fn call_mints_value(call: &ExprCall) -> bool {
    let Some(callee) = last_segment(&call.func) else {
        return false;
    };
    let Expr::Path(func_path) = &*call.func else {
        return false;
    };
    let segments = path_segments(&func_path.path);
    let Some((_, prefix)) = segments.split_last() else {
        return false;
    };
    let rooted_mint = prefix
        .iter()
        .any(|segment| MINTING_ROOTS.contains(&segment.as_str()));
    let rng_root = prefix.iter().any(|segment| {
        matches!(
            segment.as_str(),
            "rand"
                | "fastrand"
                | "thread_rng"
                | "OsRng"
                | "Rng"
                | "SmallRng"
                | "StdRng"
                | "ChaCha"
                | "secure_random"
        )
    });
    (rooted_mint && MINTING_METHODS.contains(&callee.as_str())) || rng_root
}

/// `take` adapters bound the read: `file.take(limit).read_to_end` is legal.
fn receiver_is_take_bounded(expr: &Expr) -> bool {
    let Expr::MethodCall(call) = expr else {
        return false;
    };
    call.method == "take" || receiver_is_take_bounded(&call.receiver)
}

fn call_path_is(expr: &Expr, allowed: &[&str]) -> bool {
    let Expr::Path(func_path) = expr else {
        return false;
    };
    let joined = path_segments(&func_path.path).join("::");
    allowed.contains(&joined.as_str())
}

struct InvariantPolicy<'a> {
    path: &'a str,
    lines: Vec<String>,
    boundary_io: bool,
    loop_io: bool,
    fn_stack: Vec<String>,
    loop_depth: usize,
    violations: Vec<Violation>,
}

impl InvariantPolicy<'_> {
    fn record(&mut self, rule: &'static str, span: Span, detail: impl Into<String>) {
        self.violations.push(Violation::new(
            rule,
            format!("{}:{}", self.path, span.start().line),
            detail,
        ));
    }

    fn inside_constructor(&self) -> bool {
        self.fn_stack
            .last()
            .is_some_and(|name| is_constructor_name(name))
    }

    fn has_marker(&self, span: Span, marker: &str) -> bool {
        let needle = format!("behavior-contract: {marker}");
        let line = span.start().line;
        // Marker may sit on the flagged line or the three lines above (one comment block).
        for index in (line.saturating_sub(3))..=line {
            if index == 0 {
                continue;
            }
            if self
                .lines
                .get(index - 1)
                .is_some_and(|text| text.contains(&needle))
            {
                return true;
            }
        }
        false
    }

    fn inspect_wire_type(
        &mut self,
        name: &syn::Ident,
        vis: &syn::Visibility,
        attrs: &[syn::Attribute],
    ) {
        let is_pub = matches!(vis, syn::Visibility::Public(_));
        let name_text = name.to_string();
        let wire_shaped = ["Request", "Command", "Intent", "Envelope"]
            .iter()
            .any(|suffix| name_text.ends_with(suffix));
        if !is_pub || !wire_shaped {
            return;
        }
        let derives_deserialize = attrs
            .iter()
            .filter(|attr| attr.path().is_ident("derive"))
            .flat_map(|attr| super::rust_source::meta_arguments(&attr.meta))
            .flatten()
            .any(|meta| matches!(meta, Meta::Path(path) if path.is_ident("Deserialize")));
        if !derives_deserialize {
            return;
        }
        let checked = attrs
            .iter()
            .filter(|attr| attr.path().is_ident("serde"))
            .flat_map(|attr| super::rust_source::meta_arguments(&attr.meta))
            .flatten()
            .any(|meta| match meta {
                Meta::NameValue(value) => {
                    value.path.is_ident("try_from")
                        || value.path.is_ident("from")
                        || value.path.is_ident("remote")
                }
                Meta::List(list) => {
                    list.path.is_ident("try_from")
                        || list.path.is_ident("from")
                        || list.path.is_ident("remote")
                }
                Meta::Path(path) => path.is_ident("try_from") || path.is_ident("from"),
            });
        if !checked {
            self.record(
                "rust-checked-wire",
                name.span(),
                format!(
                    "{name_text} derives Deserialize without a checked `try_from`/`from` wire \
                     entry; wire inputs must pass a single validating constructor (audit I7)"
                ),
            );
        }
    }

    fn inspect_identity_target(&mut self, name: &str, value: &Expr, span: Span) {
        if self.inside_constructor()
            || !is_identity_name(name)
            || self.has_marker(span, IDENTITY_MINT_MARKER)
        {
            return;
        }
        if is_forbidden_identity_rhs(value) {
            self.record(
                "rust-identity-sentinel",
                span,
                format!(
                    "{name} is minted at the call site; identities/generations/fences/validators \
                     are issued by their owning constructor or engine (audit I1)"
                ),
            );
        }
    }

    fn inspect_boundary_read_call(&mut self, call: &ExprCall) {
        let banned = [
            "fs::read",
            "std::fs::read",
            "fs::read_to_string",
            "std::fs::read_to_string",
            "tokio::fs::read",
            "tokio::fs::read_to_string",
            "async_fs::read",
        ];
        if !call_path_is(&call.func, &banned) {
            return;
        }
        if self.in_bounded_owner() || self.has_marker(call.func.span(), BOUNDED_IO_MARKER) {
            return;
        }
        let Expr::Path(func_path) = &*call.func else {
            return;
        };
        let callee_path = path_segments(&func_path.path).join("::");
        self.record(
            "rust-bounded-io",
            call.func.span(),
            format!(
                "{callee_path} materializes bytes without a proven budget; boundary crates \
                 stream or call a `read_bounded` owner, or document the bound with \
                 `// behavior-contract: {BOUNDED_IO_MARKER}: <limit>` (audit I5)"
            ),
        );
    }

    fn inspect_boundary_read_method(&mut self, call: &ExprMethodCall) {
        let method = call.method.to_string();
        if !matches!(method.as_str(), "read_to_end" | "read_to_string") {
            return;
        }
        if receiver_is_take_bounded(&call.receiver)
            || self.in_bounded_owner()
            || self.has_marker(call.method.span(), BOUNDED_IO_MARKER)
        {
            return;
        }
        self.record(
            "rust-bounded-io",
            call.method.span(),
            format!(
                "{method} materializes bytes without a proven budget; boundary crates stream or \
                 call a `read_bounded` owner, or document the bound with \
                 `// behavior-contract: {BOUNDED_IO_MARKER}: <limit>` (audit I5)"
            ),
        );
    }

    fn in_bounded_owner(&self) -> bool {
        self.fn_stack.last().is_some_and(|name| {
            name.starts_with("read_bounded") || name.starts_with("read_limited")
        })
    }

    fn inspect_loop_transport(&mut self, expr: &Expr) {
        if self.loop_depth == 0 {
            return;
        }
        let callee = if let Expr::Call(call) = expr {
            last_segment(&call.func)
        } else if let Expr::MethodCall(call) = expr {
            Some(call.method.to_string())
        } else {
            None
        };
        let Some(name) = callee else { return };
        let short = name.trim_start_matches("r#");
        if !TRANSPORT_CALLEES.contains(&short) {
            return;
        }
        let span = expr.span();
        if self.has_marker(span, LOOP_IO_MARKER) {
            return;
        }
        self.record(
            "rust-loop-boundary-io",
            span,
            format!(
                "{short} performs remote/transport I/O inside a loop or collection iteration; \
                 batch or paginate the boundary (audit I5) or document with \
                 `// behavior-contract: {LOOP_IO_MARKER}: <reason>`"
            ),
        );
    }
}

impl<'ast> Visit<'ast> for InvariantPolicy<'_> {
    fn visit_item(&mut self, item: &'ast Item) {
        let target: Option<(&syn::Ident, &syn::Visibility, &[syn::Attribute])> =
            if let Item::Struct(item) = item {
                Some((&item.ident, &item.vis, &item.attrs))
            } else if let Item::Enum(item) = item {
                Some((&item.ident, &item.vis, &item.attrs))
            } else {
                None
            };
        if let Some((ident, vis, attrs)) = target {
            self.inspect_wire_type(ident, vis, attrs);
        }
        visit::visit_item(self, item);
    }

    fn visit_item_fn(&mut self, item: &'ast ItemFn) {
        let name = item.sig.ident.to_string();
        if name.ends_with("_from_message") || name.ends_with("_to_message") {
            self.record(
                "rust-error-sniff",
                item.sig.ident.span(),
                format!(
                    "{name} decodes disposition from message text; keep the typed code/category \
                     end to end (audit I2)"
                ),
            );
        }
        self.fn_stack.push(name);
        visit::visit_item_fn(self, item);
        self.fn_stack.pop();
    }

    fn visit_impl_item_fn(&mut self, item: &'ast syn::ImplItemFn) {
        self.fn_stack.push(item.sig.ident.to_string());
        visit::visit_impl_item_fn(self, item);
        self.fn_stack.pop();
    }

    fn visit_trait_item_fn(&mut self, item: &'ast syn::TraitItemFn) {
        self.fn_stack.push(item.sig.ident.to_string());
        visit::visit_trait_item_fn(self, item);
        self.fn_stack.pop();
    }

    fn visit_local(&mut self, local: &'ast Local) {
        if let Some(init) = &local.init
            && let Pat::Ident(ident) = &local.pat
        {
            self.inspect_identity_target(&ident.ident.to_string(), &init.expr, ident.span());
        }
        visit::visit_local(self, local);
    }

    fn visit_field_value(&mut self, field: &'ast syn::FieldValue) {
        if let Member::Named(name) = &field.member {
            self.inspect_identity_target(&name.to_string(), &field.expr, name.span());
        }
        visit::visit_field_value(self, field);
    }

    fn visit_expr_assign(&mut self, expr: &'ast syn::ExprAssign) {
        if let Expr::Field(field) = &*expr.left
            && let Member::Named(name) = &field.member
        {
            self.inspect_identity_target(&name.to_string(), &expr.right, name.span());
        }
        visit::visit_expr_assign(self, expr);
    }

    fn visit_expr_method_call(&mut self, expr: &'ast ExprMethodCall) {
        let method = expr.method.to_string();
        if matches!(method.as_str(), "contains" | "starts_with" | "ends_with")
            && (error_text_at_decision_position(&expr.receiver)
                || expr.args.iter().any(error_text_at_decision_position))
        {
            self.record(
                "rust-error-sniff",
                expr.method.span(),
                "branching on rendered error text; keep typed error codes/dispositions end to \
                 end (audit I2)",
            );
        }
        if self.boundary_io {
            self.inspect_boundary_read_method(expr);
        }
        if self.loop_io {
            self.inspect_loop_transport(&Expr::MethodCall(expr.clone()));
            if ITERATOR_ADAPTERS.contains(&method.as_str()) {
                // Iteration context covers only the closure arguments — the receiver
                // chain (e.g. `fetch_object()?.map(..)`) is not inside the iteration.
                self.visit_expr(&expr.receiver);
                for arg in &expr.args {
                    if matches!(arg, Expr::Closure(_)) {
                        self.loop_depth += 1;
                        self.visit_expr(arg);
                        self.loop_depth -= 1;
                    } else {
                        self.visit_expr(arg);
                    }
                }
                return;
            }
        }
        visit::visit_expr_method_call(self, expr);
    }

    fn visit_expr_call(&mut self, expr: &'ast ExprCall) {
        if self.boundary_io {
            self.inspect_boundary_read_call(expr);
        }
        if self.loop_io {
            self.inspect_loop_transport(&Expr::Call(expr.clone()));
        }
        visit::visit_expr_call(self, expr);
    }

    fn visit_expr_binary(&mut self, expr: &'ast ExprBinary) {
        if matches!(expr.op, BinOp::Eq(_) | BinOp::Ne(_))
            && (error_text_at_decision_position(&expr.left)
                || error_text_at_decision_position(&expr.right))
        {
            self.record(
                "rust-error-sniff",
                expr.op.span(),
                "comparing rendered error text; keep typed error codes/dispositions end to end \
                 (audit I2)",
            );
        }
        visit::visit_expr_binary(self, expr);
    }

    fn visit_expr_match(&mut self, expr: &'ast ExprMatch) {
        if error_text_at_decision_position(&expr.expr) {
            self.record(
                "rust-error-sniff",
                expr.expr.span(),
                "matching on rendered error text; keep typed error codes/dispositions end to end \
                 (audit I2)",
            );
        }
        visit::visit_expr_match(self, expr);
    }

    fn visit_expr_for_loop(&mut self, expr: &'ast syn::ExprForLoop) {
        self.loop_depth += 1;
        visit::visit_expr_for_loop(self, expr);
        self.loop_depth -= 1;
    }

    fn visit_expr_while(&mut self, expr: &'ast syn::ExprWhile) {
        self.loop_depth += 1;
        visit::visit_expr_while(self, expr);
        self.loop_depth -= 1;
    }

    fn visit_expr_loop(&mut self, expr: &'ast syn::ExprLoop) {
        self.loop_depth += 1;
        visit::visit_expr_loop(self, expr);
        self.loop_depth -= 1;
    }
}
