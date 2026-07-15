//! Behavior Contract
//!
//! Capability: prove reqwest/Rustls HTTPS against a local fixture with streaming timeout,
//! S3-shaped list pagination, and conditional put — no public network and no native TLS.
//!
//! Scenarios:
//! - Given a local HTTPS fixture, when `/echo` is requested, then the body matches.
//! - Given a slow stream route and short timeout, when requested, then the client times out.
//! - Given S3-shaped list pages, when walked, then all keys are observed in order.
//! - Given conditional put with `If-None-Match: *`, when the object exists, then status is 412.
//!
//! Observable outcomes: successful echo, timeout error, four keys, 412 precondition failure.
//! Excludes: real AWS account, Docker, production DI, device `adb reverse`.

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use lomo_feasibility::{
        HttpsFixture, fixture_client, probe_echo, probe_s3_conditional_put,
        probe_s3_list_pagination, probe_stream_timeout, reset_http_probe_state,
    };

    #[test]
    fn https_fixture_supports_echo_timeout_s3_list_and_conditional_put() {
        reset_http_probe_state();
        let fixture = HttpsFixture::start().expect("fixture starts");
        let client =
            fixture_client(fixture.ca_pem(), Duration::from_secs(2)).expect("client builds");
        probe_echo(&client, &fixture.base_url()).expect("echo");
        probe_stream_timeout(&fixture.base_url(), fixture.ca_pem()).expect("timeout");
        let keys = probe_s3_list_pagination(&client, &fixture.base_url()).expect("list");
        assert_eq!(keys, 4);
        probe_s3_conditional_put(&client, &fixture.base_url()).expect("conditional put");
        assert!(fixture.stats().requests >= 4);
    }
}
