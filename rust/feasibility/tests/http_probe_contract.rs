//! Behavior Contract
//!
//! Capability: prove reqwest/Rustls HTTPS against a local path-style S3-shaped fixture covering
//! the P0-08 wire matrix — no public network and no native TLS.
//!
//! Scenarios:
//! - Given a local HTTPS fixture, when the full wire matrix runs, then echo, cert rejection,
//!   stream timeout, stream upload, path-style list, pagination, conditional PUT, multipart abort,
//!   and SigV4-shaped signing all pass.
//!
//! Observable outcomes: `run_http_wire_matrix` returns `Ok(())`; fixture request counter advances.
//! Excludes: full AWS SDK crate (volume-constrained; `SigV4` shape proven), Docker, real accounts.

#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::too_many_lines,
    reason = "feasibility contract harness fails closed with panics on missing probe facts"
)]
mod tests {
    use lomo_feasibility::{HttpsFixture, reset_http_probe_state, run_http_wire_matrix};

    #[test]
    fn https_wire_matrix_covers_p0_08_capabilities() {
        reset_http_probe_state();
        let fixture = HttpsFixture::start().expect("fixture starts");
        run_http_wire_matrix(&fixture).expect("wire matrix");
        assert!(fixture.stats().requests >= 8);
        // Drop-cancel path must surface a request-scoped stream write failure (not only a
        // global counter that timeout workers can race-increment).
        assert!(
            !fixture.stats().failed_stream_ids.is_empty(),
            "server must record at least one failed stream id after client cancel"
        );
        assert!(
            fixture.stats().stream_write_failures >= 1,
            "server must observe at least one stream write failure after client cancel"
        );
    }
}
