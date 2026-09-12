use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use anyhow::{Result, bail};

use crate::workspace::Workspace;

/// Methods on `lomo_store::Store` that are intentionally internal, low-level constructors,
/// or managed by external sync/rebuild pipelines rather than the general UI store handle.
const STORE_ALLOWLIST: &[(&str, &str)] = &[
    (
        "open",
        "Constructs direct Store instance during engine bootstrap",
    ),
    (
        "open_projection",
        "Constructs SAF Store instance during engine bootstrap",
    ),
    ("workspace_root", "Low-level path accessor"),
    ("open_info", "Bootstrap diagnostic metadata"),
    ("high_water_revision", "Internal revision clock accessor"),
    ("event_sequence", "Internal event sequence accessor"),
    ("write_gate", "Internal concurrency gate accessor"),
    (
        "apply_memo_command_with_created_at",
        "Internal timestamp-injected variant of apply_memo_command",
    ),
    (
        "query_memos_with_boundary",
        "Session boundary query implementation, called internally",
    ),
    (
        "query_memos_starting_at",
        "Positional page start; FFI query_memos maps wire args onto this owner method",
    ),
    (
        "get_memo_projection",
        "Internal projection-only query for get_memo",
    ),
    (
        "get_projected_memo",
        "Internal projected snapshot query for SAF",
    ),
    (
        "stats",
        "Low-level aggregate stats, exposed via sidebar_projection",
    ),
    (
        "create_received_memo",
        "Managed exclusively by LAN peer-to-peer receive pipeline",
    ),
    (
        "snapshot_sync_view",
        "Managed exclusively by remote sync provider pipeline",
    ),
    (
        "apply_local_sync_batch",
        "Managed exclusively by remote sync provider pipeline",
    ),
    (
        "prepare_sync_apply",
        "Managed exclusively by remote sync provider pipeline",
    ),
    (
        "commit_sync_apply",
        "Managed exclusively by remote sync provider pipeline",
    ),
    (
        "rebuild",
        "Direct full rebuild constructor, exposed via start_rebuild on handle",
    ),
    (
        "projection_clock",
        "Internal publication clock accessor used by WorkspaceSession recovery",
    ),
    (
        "restore_clock_floor",
        "Session recovery restores the private clock floor before query rebuild",
    ),
    (
        "publish_document",
        "Application document transaction publishes projection facts after durable Markdown I/O",
    ),
    (
        "acknowledge_rebuilt_publication",
        "Session rebuild acknowledges a frozen publication whose physical result was reindexed",
    ),
];

/// Methods on `StoreHandle` that are lifecycle constructors, path accessors, or consumed
/// by `WorkspaceNativeAdapter` / dark-build snooze pipelines instead of `StoreNativeBridge`.
const STORE_HANDLE_ALLOWLIST: &[(&str, &str)] = &[
    ("new", "Direct workspace StoreHandle constructor"),
    ("new_saf", "SAF workspace StoreHandle constructor"),
    ("workspace_root", "Low-level path accessor"),
    (
        "start_rebuild",
        "Direct rebuild method, consumed by BoltFfiNativeEnginePort/StorePort",
    ),
    (
        "begin_saf_projection_rebuild",
        "Managed by WorkspaceNativeAdapter SAF rebuild job",
    ),
    (
        "append_saf_projection_rebuild_page",
        "Managed by WorkspaceNativeAdapter SAF rebuild job",
    ),
    (
        "append_saf_trash_projection_rebuild_page",
        "Managed by WorkspaceNativeAdapter SAF rebuild job",
    ),
    (
        "append_saf_history_projection_rebuild_page",
        "Managed by WorkspaceNativeAdapter SAF rebuild job",
    ),
    (
        "finish_saf_projection_rebuild",
        "Managed by WorkspaceNativeAdapter SAF rebuild job",
    ),
    (
        "abort_saf_projection_rebuild",
        "Managed by WorkspaceNativeAdapter SAF rebuild job",
    ),
];

/// Methods on `StoreNativeBridge` that remain wired through engine adapters after the
/// repository cutover to `SessionNativeBridge`. They are not dormant product features; they are
/// adapter plumbing that repositories must not call. Remove an entry when the store JNI method is
/// deleted or a non-adapter production caller appears.
const UNINVOKED_BRIDGE_ALLOWLIST: &[(&str, &str)] = &[
    (
        "commitSafProjectionMutation",
        "SAF projection commit is owned by WorkspaceSession; store JNI stays adapter plumbing",
    ),
    (
        "listMemoHistory",
        "History pages are owned by sessionListHistory; store listMemoHistory stays adapter plumbing",
    ),
    (
        "memoStatisticsRows",
        "Statistics are owned by sessionStatistics; store memoStatisticsRows stays adapter plumbing",
    ),
    (
        "selectMemoPromotePlans",
        "Promote planning is owned by the application session; store JNI stays adapter plumbing",
    ),
    (
        "sourceDocumentFingerprint",
        "Write baselines are owned by the application session; store JNI stays adapter plumbing",
    ),
];

/// Kotlin files that are pure bridge declarations or engine delegation forwarders.
/// They never count as real domain/production callers themselves, but a bridge method
/// they invoke inside a production-invoked function is accepted via one bounded hop
/// (see `find_transitive_production_caller`).
const EXCLUDED_FORWARDERS: &[&str] = &[
    "StoreNativeBridge.kt",
    "EngineFailureConvertingStoreBridge.kt",
    "RustEngineAdapter.kt",
    "BoltFfiNativeEnginePort.kt",
    "ManagedEngineCapabilities.kt",
    "BoltFfiStorePort.kt",
];

pub fn check_ffi_parity(workspace: &Workspace) -> Result<()> {
    let store_lib_path = workspace.root.join("crates/lomo-store/src/lib.rs");
    let store_ffi_path = workspace.root.join("crates/lomo-native/src/store_ffi.rs");
    let native_lib_path = workspace.root.join("crates/lomo-native/src/lib.rs");
    let kotlin_bridge_path = workspace
        .root
        .join("apps/android/data/src/engine/store/StoreNativeBridge.kt");

    for (path, description) in [
        (&store_lib_path, "store lib"),
        (&store_ffi_path, "store ffi"),
        (&native_lib_path, "native lib"),
        (&kotlin_bridge_path, "kotlin store bridge"),
    ] {
        if !path.is_file() {
            bail!("missing {description}: {}", path.display());
        }
    }

    let store_methods = extract_impl_store_methods(&store_lib_path)?;
    let handle_methods = extract_impl_store_handle_methods(&store_ffi_path)?;
    let exported_engine_methods = extract_exported_lomo_engine_methods(&native_lib_path)?;
    let kotlin_bridge_methods = extract_kotlin_bridge_methods(&kotlin_bridge_path)?;

    let store_allowlist_map: BTreeMap<&str, &str> = STORE_ALLOWLIST.iter().copied().collect();
    let handle_allowlist_map: BTreeMap<&str, &str> =
        STORE_HANDLE_ALLOWLIST.iter().copied().collect();

    let mut violations = Vec::new();

    collect_stale_allowlist_entries(
        &store_methods,
        &store_allowlist_map,
        "STORE_ALLOWLIST",
        "Store",
        &mut violations,
    );
    collect_stale_allowlist_entries(
        &handle_methods,
        &handle_allowlist_map,
        "STORE_HANDLE_ALLOWLIST",
        "StoreHandle",
        &mut violations,
    );

    // 1. Every public Store method must either be in STORE_ALLOWLIST or implemented on StoreHandle
    for method in &store_methods {
        if store_allowlist_map.contains_key(method.as_str()) {
            continue;
        }
        if !handle_methods.contains(method) {
            violations.push(format!(
                "Rust Store capability `{method}` in crates/lomo-store/src/lib.rs is not exposed on StoreHandle in crates/lomo-native/src/store_ffi.rs and not in STORE_ALLOWLIST"
            ));
        }
    }

    // 2. Every StoreHandle method exported via BoltFFI must either be in STORE_HANDLE_ALLOWLIST or exposed in Kotlin StoreNativeBridge
    for method in &handle_methods {
        if handle_allowlist_map.contains_key(method.as_str()) {
            continue;
        }
        // Only methods exported across BoltFFI via #[export] impl LomoEngine are expected in StoreNativeBridge.
        // Pure Rust internal helpers on StoreHandle not exported to foreign callers are excluded.
        if !exported_engine_methods.contains(method) {
            continue;
        }
        let camel = snake_to_camel(method);
        if !kotlin_bridge_methods.contains(&camel) {
            violations.push(format!(
                "Exported StoreHandle method `{method}` (expected Kotlin `{camel}`) in crates/lomo-native/src/store_ffi.rs is not declared in apps/android/data/src/engine/store/StoreNativeBridge.kt and not in STORE_HANDLE_ALLOWLIST"
            ));
        }
    }

    // 3. Every Kotlin StoreNativeBridge method must have a backing StoreHandle method and be exported on LomoEngine
    for method in &kotlin_bridge_methods {
        let snake = camel_to_snake(method);
        if !handle_methods.contains(&snake) {
            violations.push(format!(
                "Kotlin StoreNativeBridge method `{method}` (expected Rust `{snake}`) in apps/android/data/src/engine/store/StoreNativeBridge.kt has no backing method on StoreHandle in crates/lomo-native/src/store_ffi.rs"
            ));
        }
        if !exported_engine_methods.contains(&snake) {
            violations.push(format!(
                "Kotlin StoreNativeBridge method `{method}` (expected Rust `{snake}`) is declared in apps/android/data/src/engine/store/StoreNativeBridge.kt but not exported in `#[export] impl LomoEngine` in crates/lomo-native/src/lib.rs"
            ));
        }
    }

    // 4. Closed-loop Kotlin bridge reachability check
    if let Err(reachability_err) =
        check_kotlin_bridge_reachability(workspace, &kotlin_bridge_methods)
    {
        violations.push(reachability_err.to_string());
    }

    if !violations.is_empty() {
        use std::fmt::Write as _;
        let mut message =
            String::from("FFI Parity and Reachability check failed with violations:\n");
        for v in violations {
            writeln!(message, "  - {v}")?;
        }
        bail!("{message}");
    }

    crate::util::emit_stderr(format_args!(
        "xtask: FFI parity verified ({} Store methods, {} StoreHandle methods, {} exported engine methods, {} Kotlin bridge methods)",
        store_methods.len(),
        handle_methods.len(),
        exported_engine_methods.len(),
        kotlin_bridge_methods.len()
    ));

    Ok(())
}

fn collect_stale_allowlist_entries(
    methods: &BTreeSet<String>,
    allowlist: &BTreeMap<&str, &str>,
    list_name: &str,
    owner: &str,
    violations: &mut Vec<String>,
) {
    for allowlisted in allowlist.keys() {
        if !methods.contains(*allowlisted) {
            violations.push(format!(
                "Method `{allowlisted}` in {list_name} is not implemented in {owner}. Remove stale allowlist entry"
            ));
        }
    }
}

fn extract_impl_store_methods(path: &Path) -> Result<BTreeSet<String>> {
    let content = fs::read_to_string(path)?;
    let mut methods = BTreeSet::new();
    let mut in_impl_store = false;

    for line in content.lines() {
        if line.starts_with("impl Store {") {
            in_impl_store = true;
            continue;
        }

        if in_impl_store {
            if line.starts_with('}') {
                in_impl_store = false;
                continue;
            }

            if line.starts_with("    pub fn ") || line.starts_with("    pub const fn ") {
                let trimmed = line.trim();
                let name = trimmed
                    .trim_start_matches("pub fn ")
                    .trim_start_matches("pub const fn ")
                    .split('(')
                    .next()
                    .unwrap_or("")
                    .split('<')
                    .next()
                    .unwrap_or("")
                    .trim();
                if !name.is_empty() {
                    methods.insert(name.to_owned());
                }
            }
        }
    }

    Ok(methods)
}

fn extract_impl_store_handle_methods(path: &Path) -> Result<BTreeSet<String>> {
    let content = fs::read_to_string(path)?;
    let mut methods = BTreeSet::new();
    let mut in_impl_store_handle = false;

    for line in content.lines() {
        if line.starts_with("impl StoreHandle {") {
            in_impl_store_handle = true;
            continue;
        }

        if in_impl_store_handle {
            if line.starts_with('}') {
                in_impl_store_handle = false;
                continue;
            }

            if line.starts_with("    pub fn ") || line.starts_with("    pub const fn ") {
                let trimmed = line.trim();
                let name = trimmed
                    .trim_start_matches("pub fn ")
                    .trim_start_matches("pub const fn ")
                    .split('(')
                    .next()
                    .unwrap_or("")
                    .split('<')
                    .next()
                    .unwrap_or("")
                    .trim();
                if !name.is_empty() {
                    methods.insert(name.to_owned());
                }
            }
        }
    }

    Ok(methods)
}

fn extract_exported_lomo_engine_methods(path: &Path) -> Result<BTreeSet<String>> {
    let content = fs::read_to_string(path)?;
    let mut methods = BTreeSet::new();
    let mut saw_export = false;
    let mut in_exported_impl = false;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed == "#[export]" {
            saw_export = true;
            continue;
        }

        if saw_export {
            if trimmed.is_empty() || trimmed.starts_with("//") {
                continue;
            }
            if line.starts_with("impl LomoEngine {") {
                in_exported_impl = true;
                saw_export = false;
                continue;
            }
            saw_export = false;
        }

        if in_exported_impl {
            if line.starts_with('}') {
                in_exported_impl = false;
                continue;
            }

            if line.starts_with("    pub fn ") || line.starts_with("    pub const fn ") {
                let trimmed_fn = line.trim();
                let name = trimmed_fn
                    .trim_start_matches("pub fn ")
                    .trim_start_matches("pub const fn ")
                    .split('(')
                    .next()
                    .unwrap_or("")
                    .split('<')
                    .next()
                    .unwrap_or("")
                    .trim();
                if !name.is_empty() {
                    methods.insert(name.to_owned());
                }
            }
        }
    }

    Ok(methods)
}

/// Collects exported method names from `StoreNativeBridge`.
///
/// # Errors
///
/// Returns an error when the bridge source cannot be read.
pub fn extract_kotlin_bridge_methods(path: &Path) -> Result<BTreeSet<String>> {
    let content = fs::read_to_string(path)?;
    let mut methods = BTreeSet::new();
    let mut in_bridge_interface = false;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("internal interface StoreNativeReadBridge")
            || trimmed.starts_with("internal interface StoreNativeMutationBridge")
        {
            in_bridge_interface = true;
            continue;
        }

        if in_bridge_interface {
            if trimmed.starts_with('}') {
                in_bridge_interface = false;
                continue;
            }
            if trimmed.starts_with("fun ") {
                let name = trimmed
                    .trim_start_matches("fun ")
                    .split('(')
                    .next()
                    .unwrap_or("")
                    .split('<')
                    .next()
                    .unwrap_or("")
                    .trim();
                if !name.is_empty() {
                    methods.insert(name.to_owned());
                }
            }
        }
    }

    Ok(methods)
}

pub fn check_kotlin_bridge_reachability(
    workspace: &Workspace,
    kotlin_bridge_methods: &BTreeSet<String>,
) -> Result<()> {
    let (violations, active_count) =
        collect_bridge_reachability_violations(workspace, kotlin_bridge_methods)?;

    if !violations.is_empty() {
        use std::fmt::Write as _;
        let mut message = String::from("Bridge Reachability check failed with violations:\n");
        for v in violations {
            writeln!(message, "  - {v}")?;
        }
        bail!("{message}");
    }

    crate::util::emit_stderr(format_args!(
        "xtask: Bridge reachability verified ({} active methods with production callers, {} allowlisted dormant capabilities)",
        active_count,
        UNINVOKED_BRIDGE_ALLOWLIST.len()
    ));

    Ok(())
}

fn collect_bridge_reachability_violations(
    workspace: &Workspace,
    kotlin_bridge_methods: &BTreeSet<String>,
) -> Result<(Vec<String>, usize)> {
    let scan_roots = [
        workspace.root.join("apps/android/data/src"),
        workspace.root.join("apps/android/domain/src"),
        workspace.root.join("apps/android/app/src"),
        workspace.root.join("apps/android/ui-components/src"),
    ];

    let mut kt_files = Vec::new();
    for root in &scan_roots {
        collect_kotlin_sources(root, &mut kt_files)?;
    }

    let mut production_sources = Vec::with_capacity(kt_files.len());
    let mut forwarder_sources = Vec::new();
    for file in &kt_files {
        let content = fs::read_to_string(file)?;
        let stripped = strip_comments_and_strings(&content);
        let rel_path = file
            .strip_prefix(&workspace.root)
            .unwrap_or(file)
            .to_string_lossy()
            .to_string();
        let file_name = file
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if EXCLUDED_FORWARDERS.contains(&file_name) {
            forwarder_sources.push((rel_path, stripped));
        } else {
            production_sources.push((rel_path, stripped));
        }
    }

    let allowlist_map: BTreeMap<&str, &str> = UNINVOKED_BRIDGE_ALLOWLIST.iter().copied().collect();

    let mut violations = Vec::new();
    let mut active_count = 0;

    // Stale allowlist check (Tail deletion)
    for allowlisted_method in allowlist_map.keys() {
        if !kotlin_bridge_methods.contains(*allowlisted_method) {
            violations.push(format!(
                "Method `{allowlisted_method}` in UNINVOKED_BRIDGE_ALLOWLIST is not declared in StoreNativeBridge. Remove stale allowlist entry"
            ));
        }
    }

    for method in kotlin_bridge_methods {
        let mut callers = Vec::new();
        for (rel_path, stripped) in &production_sources {
            if contains_method_call(stripped, method) {
                callers.push(rel_path.as_str());
            }
        }

        let transitive_callers: Vec<String> = if callers.is_empty() {
            find_transitive_production_caller(&forwarder_sources, &production_sources, method)
        } else {
            Vec::new()
        };

        let is_allowlisted = allowlist_map.contains_key(method.as_str());

        if callers.is_empty() && transitive_callers.is_empty() {
            if !is_allowlisted {
                violations.push(format!(
                    "Kotlin StoreNativeBridge method `{method}` has 0 production callers outside engine plumbing. It must be called by a repository, coordinator, or command pipeline, or explicitly audited in UNINVOKED_BRIDGE_ALLOWLIST"
                ));
            }
        } else {
            if is_allowlisted {
                let wired_via = if callers.is_empty() {
                    transitive_callers.join(", ")
                } else {
                    callers.join(", ")
                };
                violations.push(format!(
                    "Kotlin StoreNativeBridge method `{method}` is in UNINVOKED_BRIDGE_ALLOWLIST but now has production callers: [{wired_via}]. Remove it from the allowlist (tail deletion)"
                ));
            }
            active_count += 1;
        }
    }

    Ok((violations, active_count))
}

fn collect_kotlin_sources(dir: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
    if !dir.is_dir() {
        return Ok(());
    }
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if name == "test" || name.starts_with('.') {
                continue;
            }
            collect_kotlin_sources(&path, files)?;
        } else if path.is_file() && path.extension().is_some_and(|ext| ext == "kt") {
            files.push(path);
        }
    }
    Ok(())
}

/// Strips Kotlin comments and string literals so call-site scans see only code.
#[must_use]
pub fn strip_comments_and_strings(source: &str) -> String {
    let mut result = String::with_capacity(source.len());
    let chars: Vec<char> = source.chars().collect();
    let mut i = 0;
    let len = chars.len();

    let char_at = |idx: usize| chars.get(idx).copied();

    while i < len {
        if char_at(i) == Some('/') && char_at(i + 1) == Some('/') {
            i += 2;
            while i < len && char_at(i) != Some('\n') {
                i += 1;
            }
        } else if char_at(i) == Some('/') && char_at(i + 1) == Some('*') {
            i += 2;
            while i + 1 < len && !(char_at(i) == Some('*') && char_at(i + 1) == Some('/')) {
                i += 1;
            }
            if i + 1 < len {
                i += 2;
            }
        } else if char_at(i) == Some('"')
            && char_at(i + 1) == Some('"')
            && char_at(i + 2) == Some('"')
        {
            i += 3;
            while i + 2 < len
                && !(char_at(i) == Some('"')
                    && char_at(i + 1) == Some('"')
                    && char_at(i + 2) == Some('"'))
            {
                i += 1;
            }
            if i + 2 < len {
                i += 3;
            }
        } else if char_at(i) == Some('"') {
            i += 1;
            while i < len && char_at(i) != Some('"') {
                if char_at(i) == Some('\\') && i + 1 < len {
                    i += 2;
                } else {
                    i += 1;
                }
            }
            if i < len {
                i += 1;
            }
        } else if char_at(i) == Some('\'') {
            i += 1;
            while i < len && char_at(i) != Some('\'') {
                if char_at(i) == Some('\\') && i + 1 < len {
                    i += 2;
                } else {
                    i += 1;
                }
            }
            if i < len {
                i += 1;
            }
        } else if let Some(c) = char_at(i) {
            result.push(c);
            i += 1;
        } else {
            break;
        }
    }
    result
}

/// True when `method` is invoked as a call, not a declaration, reference, or property read.
#[must_use]
pub fn contains_method_call(content: &str, method: &str) -> bool {
    let mut start = 0;
    let m_len = method.len();
    while let Some(remainder) = content.get(start..) {
        let Some(idx) = remainder.find(method) else {
            break;
        };
        let abs_idx = start + idx;
        start = abs_idx + m_len;

        let Some(prefix) = content.get(..abs_idx) else {
            continue;
        };
        let Some(suffix) = content.get(abs_idx + m_len..) else {
            continue;
        };

        // Ensure word boundary before
        if abs_idx > 0 {
            let prev_char = prefix.chars().next_back().unwrap_or(' ');
            if prev_char.is_alphanumeric() || prev_char == '_' {
                continue;
            }
        }

        // Ensure word boundary after
        let next_char = suffix.chars().next().unwrap_or(' ');
        if next_char.is_alphanumeric() || next_char == '_' {
            continue;
        }

        // Scan forwards from abs_idx + m_len skipping whitespace for '(' or '<'
        let after = suffix.trim_start();

        // Scan backwards from abs_idx skipping whitespace for '.'
        let before = prefix.trim_end();
        if before.ends_with('.') {
            if after.starts_with('(') || after.starts_with('<') {
                return true;
            }
            continue;
        }

        // Receiver-less invocation (implicit receiver inside `with`/extension scope,
        // e.g. `return selectMemoPromotePlans(...)`). A bare `name(` is a call unless
        // it is a declaration (`fun name(`) or a callable reference (`::name(`).
        // Generic receiver-less calls stay unmatched on purpose: `name < expr`
        // comparisons are textually indistinguishable from `name<T>(...)`.
        if after.starts_with('(') && !ends_with_word(before, "fun") && !before.ends_with(':') {
            return true;
        }
    }
    false
}

fn ends_with_word(text: &str, word: &str) -> bool {
    text.split_whitespace().next_back() == Some(word)
}

/// Production callers of a bridge method reached through one plumbing hop.
///
/// A method with no direct production caller is still wired when an excluded plumbing
/// file invokes it inside a function that production code itself invokes
/// (repository → `commitDocumentMutation` → bridge `commitWorkspaceDocumentFacts`).
/// An empty vector means the method stays unwired.
#[must_use]
pub fn find_transitive_production_caller(
    forwarder_sources: &[(String, String)],
    production_sources: &[(String, String)],
    method: &str,
) -> Vec<String> {
    for (_, forwarder_content) in forwarder_sources {
        for function in enclosing_functions_calling(forwarder_content, method) {
            if function == method {
                // Self-delegation inside bridge plumbing (failure-converting wrappers,
                // JNI glue) is not production wiring.
                continue;
            }
            let callers: Vec<String> = production_sources
                .iter()
                .filter(|(_, content)| contains_method_call(content, &function))
                .map(|(path, _)| path.clone())
                .collect();
            if !callers.is_empty() {
                return callers;
            }
        }
    }
    Vec::new()
}

/// Text-heuristic enclosing function detection on comment/string-stripped Kotlin: a
/// call on a line that also declares `fun <name>` belongs to `<name>`, otherwise it
/// belongs to the nearest `fun <name>` declared above it in the same file.
fn enclosing_functions_calling(content: &str, method: &str) -> Vec<String> {
    let mut functions = Vec::new();
    let mut current_function: Option<String> = None;
    for line in content.lines() {
        let declared = extract_function_declaration_name(line);
        if contains_method_call(line, method)
            && let Some(name) = declared.clone().or_else(|| current_function.clone())
            && !functions.contains(&name)
        {
            functions.push(name);
        }
        if let Some(name) = declared {
            current_function = Some(name);
        }
    }
    functions
}

fn extract_function_declaration_name(line: &str) -> Option<String> {
    let mut search_from = 0;
    while let Some(relative) = line.get(search_from..).and_then(|rest| rest.find("fun ")) {
        let absolute = search_from + relative;
        let preceded_by_word = absolute > 0
            && line
                .get(..absolute)
                .and_then(|prefix| prefix.chars().next_back())
                .is_some_and(|c| c.is_alphanumeric() || c == '_');
        if !preceded_by_word {
            let name: String = line
                .get(absolute + 4..)?
                .trim_start()
                .chars()
                .take_while(|c| c.is_alphanumeric() || *c == '_')
                .collect();
            if !name.is_empty() {
                return Some(name);
            }
        }
        search_from = absolute + 4;
    }
    None
}

/// Converts `query_memos` to `queryMemos` for Kotlin/FFI name matching.
#[must_use]
pub fn snake_to_camel(snake: &str) -> String {
    let mut camel = String::new();
    let mut capitalize_next = false;
    for c in snake.chars() {
        if c == '_' {
            capitalize_next = true;
        } else if capitalize_next {
            camel.extend(c.to_uppercase());
            capitalize_next = false;
        } else {
            camel.push(c);
        }
    }
    camel
}

/// Converts `queryMemos` to `query_memos` for Rust/FFI name matching.
#[must_use]
pub fn camel_to_snake(camel: &str) -> String {
    let mut snake = String::new();
    for (i, c) in camel.chars().enumerate() {
        if c.is_uppercase() {
            if i > 0 {
                snake.push('_');
            }
            snake.extend(c.to_lowercase());
        } else {
            snake.push(c);
        }
    }
    snake
}
