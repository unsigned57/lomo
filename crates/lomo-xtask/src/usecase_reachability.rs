use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Result, bail};

/// Domain `UseCases` that are intentionally uncalled by production UI, background worker,
/// or caller pipelines.
///
/// Under the First-Principles Gate, this must remain empty: every `UseCase` in
/// `domain/src/usecase` must either be actively wired to production pipelines or deleted
/// under tail-deletion. Merely declaring or registering a `UseCase` in DI glue code
/// does not satisfy reachability.
const UNREACHABLE_USECASE_ALLOWLIST: &[(&str, &str)] = &[];

/// Directories excluded from scanning for production callers.
const EXCLUDED_DIR_NAMES: &[&str] = &["test", "androidTest", "di"];
const USECASE_DIRECTORY: &str = "apps/android/domain/src/usecase";

/// Representation of a declared domain `UseCase`.
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct UseCaseDecl {
    pub name: String,
    pub rel_file: String,
    pub line: usize,
}

/// Verifies that every declared domain use case is reachable from a production entry point.
///
/// # Errors
///
/// Returns an error for missing sources, unreadable files, or unreachable use cases.
pub fn check_usecase_reachability(root: &Path) -> Result<()> {
    let usecase_dir = root.join(USECASE_DIRECTORY);
    if !usecase_dir.is_dir() {
        bail!(
            "missing domain usecase directory: {}",
            usecase_dir.display()
        );
    }

    let usecases = collect_usecases(root, &usecase_dir)?;
    if usecases.is_empty() {
        bail!("no UseCases discovered in {}", usecase_dir.display());
    }

    let production_sources = collect_production_sources(root)?;
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

fn collect_usecases(root: &Path, usecase_dir: &Path) -> Result<Vec<UseCaseDecl>> {
    let mut usecases = Vec::new();
    let mut entries = Vec::new();
    for entry in fs::read_dir(usecase_dir)? {
        entries.push(entry?);
    }
    entries.sort_by_key(fs::DirEntry::file_name);

    for entry in entries {
        let path = entry.path();
        if !path.is_file() || path.extension().and_then(|ext| ext.to_str()) != Some("kt") {
            continue;
        }

        let rel_file = path
            .strip_prefix(root)
            .unwrap_or(&path)
            .to_string_lossy()
            .to_string();

        let content = fs::read_to_string(&path)?;
        for (line_idx, line) in content.lines().enumerate() {
            if let Some(name) = extract_usecase_decl_from_line(line) {
                usecases.push(UseCaseDecl {
                    name,
                    rel_file: rel_file.clone(),
                    line: line_idx + 1,
                });
            }
        }
    }

    usecases.sort();
    Ok(usecases)
}

fn extract_usecase_decl_from_line(line: &str) -> Option<String> {
    let stripped = strip_line_comment(line);
    let trimmed = stripped.trim();

    for keyword in &["class ", "interface "] {
        let mut start_idx = 0;
        while let Some(remainder) = trimmed.get(start_idx..) {
            let Some(kw_pos) = remainder.find(keyword) else {
                break;
            };
            let abs_kw_pos = start_idx + kw_pos;
            let after_kw = match trimmed.get(abs_kw_pos + keyword.len()..) {
                Some(s) => s.trim_start(),
                None => break,
            };

            // Ensure word boundary before keyword
            if abs_kw_pos > 0 {
                let prev_char = trimmed
                    .get(..abs_kw_pos)
                    .and_then(|p| p.chars().next_back())
                    .unwrap_or(' ');
                if prev_char.is_alphanumeric() || prev_char == '_' {
                    start_idx = abs_kw_pos + keyword.len();
                    continue;
                }
            }

            let ident: String = after_kw
                .chars()
                .take_while(|c| c.is_alphanumeric() || *c == '_')
                .collect();

            if ident.ends_with("UseCase") {
                return Some(ident);
            }

            start_idx = abs_kw_pos + keyword.len();
        }
    }

    None
}

struct ParsedSource {
    rel_path: String,
    stripped_content: String,
}

fn collect_production_sources(root: &Path) -> Result<Vec<ParsedSource>> {
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

    let mut parsed_sources = Vec::with_capacity(kt_files.len());
    for file in &kt_files {
        let content = fs::read_to_string(file)?;
        let stripped = strip_comments_and_strings(&content);
        let rel_path = file
            .strip_prefix(root)
            .unwrap_or(file)
            .to_string_lossy()
            .to_string();
        parsed_sources.push(ParsedSource {
            rel_path,
            stripped_content: stripped,
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

fn analyze_call_graph<'a>(
    usecases: &'a [UseCaseDecl],
    sources: &'a [ParsedSource],
) -> (BTreeSet<&'a str>, BTreeMap<&'a str, BTreeSet<&'a str>>) {
    let mut direct_roots = BTreeSet::new();
    let mut dependencies: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();

    for uc in usecases {
        dependencies.entry(uc.name.as_str()).or_default();
    }

    for uc in usecases {
        for src in sources {
            let is_inside_usecase_pkg = Path::new(&src.rel_path).starts_with(USECASE_DIRECTORY);
            let is_own_file = src.rel_path == uc.rel_file;

            let contains_usage = contains_identifier_usage(&src.stripped_content, &uc.name);
            if !contains_usage {
                continue;
            }

            if !is_inside_usecase_pkg {
                // External production consumer found!
                direct_roots.insert(uc.name.as_str());
            } else if !is_own_file {
                // Usage across different files inside domain/src/usecase/
                // Find which usecases are declared in src.rel_path and add dependency
                for other_uc in usecases {
                    if other_uc.rel_file == src.rel_path {
                        dependencies
                            .entry(other_uc.name.as_str())
                            .or_default()
                            .insert(uc.name.as_str());
                    }
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

fn contains_identifier_usage(content: &str, ident: &str) -> bool {
    let ident_len = ident.len();
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("import ") || trimmed.starts_with("package ") {
            continue;
        }

        let mut start = 0;
        while let Some(remainder) = line.get(start..) {
            let Some(idx) = remainder.find(ident) else {
                break;
            };
            let abs_idx = start + idx;
            let Some(prefix) = line.get(..abs_idx) else {
                break;
            };
            let Some(suffix) = line.get(abs_idx + ident_len..) else {
                break;
            };

            // Word boundary before
            if abs_idx > 0 {
                let prev_char = prefix.chars().next_back().unwrap_or(' ');
                if prev_char.is_alphanumeric() || prev_char == '_' {
                    start = abs_idx + ident_len;
                    continue;
                }
            }

            // Word boundary after
            let next_char = suffix.chars().next().unwrap_or(' ');
            if next_char.is_alphanumeric() || next_char == '_' {
                start = abs_idx + ident_len;
                continue;
            }

            // Check if prefix ends with "class" or "interface" declaration
            let before_trimmed = prefix.trim_end();
            if is_decl_prefix(before_trimmed, "class")
                || is_decl_prefix(before_trimmed, "interface")
            {
                start = abs_idx + ident_len;
                continue;
            }

            return true;
        }
    }

    false
}

fn is_decl_prefix(before: &str, keyword: &str) -> bool {
    before.strip_suffix(keyword).is_some_and(|prefix| {
        prefix.is_empty() || prefix.chars().next_back().is_some_and(char::is_whitespace)
    })
}

fn strip_line_comment(line: &str) -> &str {
    line.find("//")
        .and_then(|idx| line.get(..idx))
        .unwrap_or(line)
}

fn skip_line_comment(chars: &[char], mut idx: usize) -> usize {
    let len = chars.len();
    while idx < len && chars.get(idx).copied() != Some('\n') {
        idx += 1;
    }
    idx
}

fn skip_block_comment(chars: &[char], mut idx: usize, result: &mut String) -> usize {
    let len = chars.len();
    while idx + 1 < len
        && !(chars.get(idx).copied() == Some('*') && chars.get(idx + 1).copied() == Some('/'))
    {
        if chars.get(idx).copied() == Some('\n') {
            result.push('\n');
        }
        idx += 1;
    }
    if idx + 1 < len { idx + 2 } else { len }
}

fn skip_triple_quoted_string(chars: &[char], mut idx: usize, result: &mut String) -> usize {
    let len = chars.len();
    while idx + 2 < len
        && !(chars.get(idx).copied() == Some('"')
            && chars.get(idx + 1).copied() == Some('"')
            && chars.get(idx + 2).copied() == Some('"'))
    {
        if chars.get(idx).copied() == Some('\n') {
            result.push('\n');
        }
        idx += 1;
    }
    if idx + 2 < len { idx + 3 } else { len }
}

fn skip_single_or_double_quote(
    chars: &[char],
    mut idx: usize,
    result: &mut String,
    delimiter: char,
) -> usize {
    let len = chars.len();
    while idx < len && chars.get(idx).copied() != Some(delimiter) {
        if chars.get(idx).copied() == Some('\\') && idx + 1 < len {
            idx += 2;
        } else {
            if chars.get(idx).copied() == Some('\n') {
                result.push('\n');
            }
            idx += 1;
        }
    }
    if idx < len { idx + 1 } else { len }
}

fn strip_comments_and_strings(source: &str) -> String {
    let mut result = String::with_capacity(source.len());
    let chars: Vec<char> = source.chars().collect();
    let mut i = 0;
    let len = chars.len();

    let char_at = |idx: usize| chars.get(idx).copied();

    while i < len {
        if char_at(i) == Some('/') && char_at(i + 1) == Some('/') {
            i = skip_line_comment(&chars, i + 2);
        } else if char_at(i) == Some('/') && char_at(i + 1) == Some('*') {
            i = skip_block_comment(&chars, i + 2, &mut result);
        } else if char_at(i) == Some('"')
            && char_at(i + 1) == Some('"')
            && char_at(i + 2) == Some('"')
        {
            i = skip_triple_quoted_string(&chars, i + 3, &mut result);
        } else if char_at(i) == Some('"') {
            i = skip_single_or_double_quote(&chars, i + 1, &mut result, '"');
        } else if char_at(i) == Some('\'') {
            i = skip_single_or_double_quote(&chars, i + 1, &mut result, '\'');
        } else if let Some(c) = char_at(i) {
            result.push(c);
            i += 1;
        } else {
            break;
        }
    }
    result
}
