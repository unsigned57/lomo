#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_native::{SyncPlannerError, plan_sync_envelope};

    const LOCAL_ONLY_S3_REQUEST_HEX: &str =
        include_str!("../../../data/testResources/rust-sync/local-only-s3-request.hex");
    const LOCAL_ONLY_UPLOAD_PLAN_HEX: &str =
        include_str!("../../../data/testResources/rust-sync/local-only-upload-plan.hex");

    #[test]
    fn boltffi_export_returns_plan_bytes_for_valid_request() {
        let output = plan_sync_envelope(decode_hex(LOCAL_ONLY_S3_REQUEST_HEX))
            .test_ok("valid request plans");

        assert_eq!(output, decode_hex(LOCAL_ONLY_UPLOAD_PLAN_HEX));
    }

    #[test]
    fn boltffi_export_surfaces_protocol_rejection() {
        let error = plan_sync_envelope(b"invalid".to_vec()).test_err("invalid request is rejected");

        assert!(matches!(error, SyncPlannerError::Rejected { .. }));
        assert!(error.to_string().contains("InvalidMagic"));
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        let value = value.trim();
        assert_eq!(value.len() % 2, 0, "hex fixture must contain whole bytes");
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let pair = std::str::from_utf8(pair).test_ok("hex fixture is ASCII");
                u8::from_str_radix(pair, 16).test_ok("hex fixture contains valid bytes")
            })
            .collect()
    }
}
