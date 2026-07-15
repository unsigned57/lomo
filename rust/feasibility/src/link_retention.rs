//! Force-reachable candidate stack markers so LTO / section-GC cannot drop HTTP/Git symbols
//! from tooling shared objects that call this function.
//!
//! ## What this proves
//!
//! Live constructor / version paths for the **candidate crates** (`git2`/libgit2, reqwest/Rustls,
//! `SQLite` via the device bundle) remain reachable after LTO. That is volume-selection evidence
//! for the tooling SO.
//!
//! ## What this does **not** prove
//!
//! Full Git smart-HTTP, push/rebase, or HTTP send/stream matrices. Those stay host-fixture
//! contracts. Cargo dependency edges alone are not volume evidence either.
//!
//! Retention checks must look for the **exact** `LOMO_LINK_MARKER_*` literals below — not
//! generic `OpenSSL` / `aws-lc` substrings (false-positive risk).

use std::time::Duration;

use git2::{Repository, Version};
use reqwest::blocking::Client;

/// Exact sentinel retained when the git2/libgit2 path is linked and executed.
pub const MARKER_GIT2: &str = "LOMO_LINK_MARKER_GIT2_v1";
/// Exact sentinel retained when the reqwest + Rustls client path is linked and executed.
pub const MARKER_REQWEST_RUSTLS: &str = "LOMO_LINK_MARKER_REQWEST_RUSTLS_v1";
/// Exact sentinel retained when the bundled `SQLite` probe path is linked and executed.
pub const MARKER_SQLITE: &str = "LOMO_LINK_MARKER_SQLITE_v1";

/// Return a non-empty diagnostic that exercises `git2` and `reqwest`/`rustls` symbols.
///
/// Must be invoked from the device-bundle entry so `release-ci` LTO retains the graph.
/// Callers must also include [`MARKER_SQLITE`] after a successful `run_sqlite_probe`.
#[must_use]
pub fn candidate_link_markers() -> String {
    // Force unique sentinels into the return value (and therefore into the SO rodata).
    let git_marker = MARKER_GIT2;
    let http_marker = MARKER_REQWEST_RUSTLS;

    // git2 / libgit2: version query + open both touch C entry points.
    let version = Version::get();
    let (major, minor, rev) = version.libgit2_version();
    let vendored = version.vendored();
    let https = version.https();
    let open = match Repository::open("/__lomo_link_retention_nonexistent__") {
        Ok(_) => "unexpected-open",
        Err(error) => {
            // Keep class discriminant so the Error path cannot be DCE'd.
            std::hint::black_box(error.class() as i32);
            "open-err"
        }
    };
    let git = format!(
        "{git_marker}:libgit2={major}.{minor}.{rev}:vendored={vendored}:https={https}:{open}"
    );

    // reqwest + rustls (no native TLS): builder + request + crypto provider.
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    std::hint::black_box(provider.cipher_suites.len());
    let http = match Client::builder()
        .use_rustls_tls()
        .timeout(Duration::from_millis(1))
        .build()
    {
        Ok(client) => {
            // Build only — no network. Keep the request type live for LTO.
            let pending = client.get("https://127.0.0.1:1/lomo-link-retention");
            let _kept: reqwest::blocking::RequestBuilder = std::hint::black_box(pending);
            format!("{http_marker}:ok")
        }
        Err(error) => {
            std::hint::black_box(error.to_string());
            format!("{http_marker}:err")
        }
    };

    format!("{git};{http}")
}

/// True when a marker string contains every required retention sentinel for git + HTTP.
#[must_use]
pub fn markers_include_git_and_http(detail: &str) -> bool {
    detail.contains(MARKER_GIT2) && detail.contains(MARKER_REQWEST_RUSTLS)
}
