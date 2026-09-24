//! Single-exit authority table (audit invariant I3): a business fact has exactly one
//! publishing/mutating exit. The table maps a call-site pattern to the owning file(s);
//! any other production Kotlin file performing the call violates the invariant.
//!
//! Rows are fail-closed: an empty pattern, an unknown owner path, or an unreadable owner
//! file is a policy error, not a skip. Rows name files (or `dir:` prefixes) under
//! `apps/android`, matched as path suffixes.

use std::iter::Peekable;
use std::path::Path;
use std::str::Chars;

use super::Violation;

struct AuthorityRow {
    /// Call-site substring looked up in comment/string-stripped Kotlin source.
    pattern: &'static str,
    /// Owning path suffixes (`dir:` entries match directory prefixes).
    owners: &'static [&'static str],
    /// Audit anchor explaining the invariant.
    anchor: &'static str,
}

const AUTHORITY_ROWS: &[AuthorityRow] = &[
    // A02: mount state is computed and published only by the engine session owner.
    AuthorityRow {
        pattern: "publishMount(",
        owners: &["apps/android/data/src/engine/ManagedEngineSession.kt"],
        anchor: "mount state has a single publisher (audit A02)",
    },
    // B03/B05: WorkManager mutations exit only through the worker/reminder scheduling
    // owners; repositories and settings never enqueue work directly.
    AuthorityRow {
        pattern: ".enqueueUniqueWork(",
        owners: &[
            "dir:apps/android/data/src/worker",
            "dir:apps/android/data/src/reminder",
        ],
        anchor: "WorkManager enqueue exits only through scheduling owners (audit B03/B05)",
    },
    AuthorityRow {
        pattern: ".enqueueUniquePeriodicWork(",
        owners: &[
            "dir:apps/android/data/src/worker",
            "dir:apps/android/data/src/reminder",
        ],
        anchor: "WorkManager enqueue exits only through scheduling owners (audit B03/B05)",
    },
    AuthorityRow {
        pattern: ".enqueueOneTimeWork(",
        owners: &[
            "dir:apps/android/data/src/worker",
            "dir:apps/android/data/src/reminder",
        ],
        anchor: "WorkManager enqueue exits only through scheduling owners (audit B03/B05)",
    },
    AuthorityRow {
        pattern: ".cancelUniqueWork(",
        owners: &[
            "dir:apps/android/data/src/worker",
            "dir:apps/android/data/src/reminder",
        ],
        anchor: "WorkManager cancellation exits only through scheduling owners (audit B03/B05)",
    },
    AuthorityRow {
        pattern: ".cancelAllWork(",
        owners: &[
            "dir:apps/android/data/src/worker",
            "dir:apps/android/data/src/reminder",
        ],
        anchor: "WorkManager cancellation exits only through scheduling owners (audit B03/B05)",
    },
    // A03: store invalidation mutates only through the store-port owners.
    AuthorityRow {
        pattern: "invalidation.setSyncing(",
        owners: &[
            "apps/android/data/src/repository/StoreMemoRepositories.kt",
            "apps/android/data/src/engine/ManagedEngineSession.kt",
        ],
        anchor: "store invalidation mutates only through store-port owners (audit A03)",
    },
    AuthorityRow {
        pattern: "invalidation.reanchor(",
        owners: &[
            "apps/android/data/src/repository/StoreMemoRepositories.kt",
            "apps/android/data/src/engine/ManagedEngineSession.kt",
        ],
        anchor: "store invalidation mutates only through store-port owners (audit A03)",
    },
    AuthorityRow {
        pattern: "invalidation.reanchorProjection(",
        owners: &[
            "apps/android/data/src/repository/StoreMemoRepositories.kt",
            "apps/android/data/src/repository/WorkspaceArchiveEdgeRepository.kt",
            "apps/android/data/src/engine/ManagedEngineSession.kt",
        ],
        anchor: "store invalidation mutates only through store-port owners (audit A03)",
    },
    AuthorityRow {
        pattern: "invalidation.register(",
        owners: &[
            "apps/android/data/src/repository/StoreMemoRepositories.kt",
            "apps/android/data/src/engine/ManagedEngineSession.kt",
        ],
        anchor: "store invalidation mutates only through store-port owners (audit A03)",
    },
    // B16/C12: exact alarm scheduling has one platform owner.
    AuthorityRow {
        pattern: ".setExactAndAllowWhileIdle(",
        owners: &["apps/android/data/src/reminder/AndroidAlarmSchedulePort.kt"],
        anchor: "exact alarm scheduling has one platform owner (audit B16)",
    },
    AuthorityRow {
        pattern: ".setAndAllowWhileIdle(",
        owners: &["apps/android/data/src/reminder/AndroidAlarmSchedulePort.kt"],
        anchor: "exact alarm scheduling has one platform owner (audit B16)",
    },
    AuthorityRow {
        pattern: ".setAlarmClock(",
        owners: &["apps/android/data/src/reminder/AndroidAlarmSchedulePort.kt"],
        anchor: "exact alarm scheduling has one platform owner (audit B16)",
    },
    AuthorityRow {
        pattern: ".setRepeating(",
        owners: &["apps/android/data/src/reminder/AndroidAlarmSchedulePort.kt"],
        anchor: "repeating alarm scheduling has one platform owner (audit B16)",
    },
    AuthorityRow {
        pattern: ".setInexactRepeating(",
        owners: &["apps/android/data/src/reminder/AndroidAlarmSchedulePort.kt"],
        anchor: "repeating alarm scheduling has one platform owner (audit B16)",
    },
    // Widget snapshot publication is owned by the app repository shell.
    AuthorityRow {
        pattern: "WidgetUpdater.updateAllWidgets(",
        owners: &["apps/android/app/src/repository/AppWidgetRepository.kt"],
        anchor: "widget updates are triggered only by the projection binder shell (audit A03)",
    },
];

/// Strips `//` comments, `/* */` comments and string literals so call-site patterns do
/// not match inside text. String templates lose their contents — interpolated calls are
/// an accepted blind spot.
fn strip_kotlin_noise(source: &str) -> String {
    let mut out = String::with_capacity(source.len());
    let mut chars = source.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '/' if chars.peek() == Some(&'/') => skip_line_comment(&mut chars, &mut out),
            '/' if chars.peek() == Some(&'*') => skip_block_comment(&mut chars),
            '"' => skip_string_literal(&mut chars, &mut out),
            _ => out.push(c),
        }
    }
    out
}

fn skip_line_comment(chars: &mut Peekable<Chars>, out: &mut String) {
    for c in chars.by_ref() {
        if c == '\n' {
            out.push('\n');
            break;
        }
    }
}

fn skip_block_comment(chars: &mut Peekable<Chars>) {
    chars.next();
    let mut prev = ' ';
    for c in chars.by_ref() {
        if prev == '*' && c == '/' {
            break;
        }
        prev = c;
    }
}

fn skip_raw_string(chars: &mut Peekable<Chars>) {
    let mut run = 0;
    for c in chars.by_ref() {
        if c == '"' {
            run += 1;
            if run == 3 {
                break;
            }
        } else {
            run = 0;
        }
    }
}

fn skip_string_literal(chars: &mut Peekable<Chars>, out: &mut String) {
    if chars.peek() == Some(&'"') {
        chars.next();
        if chars.peek() == Some(&'"') {
            chars.next();
            skip_raw_string(chars);
        }
        return;
    }
    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                chars.next();
            }
            '"' => break,
            '\n' => {
                out.push('\n');
                break;
            }
            _ => {}
        }
    }
}

fn owner_matches(path: &str, owners: &[&str]) -> bool {
    owners.iter().any(|owner| {
        owner
            .strip_prefix("dir:")
            .map_or_else(|| path == *owner, |dir| path.starts_with(dir))
    })
}

/// `files` are `(repo-relative path, source)` pairs of production Kotlin sources.
///
/// # Errors
///
/// Fails closed when a row has an empty pattern, no owners, or an owner path that does
/// not resolve to an existing file/directory under `root`.
pub fn kotlin_authority_violations(
    root: &Path,
    files: &[(String, String)],
) -> Result<Vec<Violation>, String> {
    for (index, row) in AUTHORITY_ROWS.iter().enumerate() {
        if row.pattern.trim().is_empty() {
            return Err(format!("authority row {index} has an empty pattern"));
        }
        if row.owners.is_empty() {
            return Err(format!("authority row {index} declares no owner"));
        }
        for owner in row.owners {
            let relative = owner.strip_prefix("dir:").unwrap_or(owner);
            let exists = if owner.starts_with("dir:") {
                root.join(relative).is_dir()
            } else {
                root.join(relative).is_file()
            };
            if !exists {
                return Err(format!(
                    "authority row {index} owner does not exist: {relative}"
                ));
            }
        }
    }
    let mut violations = Vec::new();
    for (path, source) in files {
        let stripped = strip_kotlin_noise(source);
        for row in AUTHORITY_ROWS {
            if owner_matches(path, row.owners) || !stripped.contains(row.pattern) {
                continue;
            }
            violations.push(Violation {
                rule: "kotlin-single-exit-authority",
                subject: path.clone(),
                detail: format!("{} calls `{}`; {}", path, row.pattern, row.anchor),
            });
        }
    }
    Ok(violations)
}
