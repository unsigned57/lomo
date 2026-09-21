//! Behavior Contract:
//! Capability: preserve the source-fingerprint type law across deserialization; owner: workspace; P0.
//! Scenarios: Given malformed digests, When decoded from JSON, Then the same values rejected by
//! the parser are rejected; canonical digests retain their exact round-trip identity.
//! Observable outcomes: decode errors and round-trip fingerprints.
//! TDD proof: RED accepts an empty digest through derived Deserialize; GREEN uses the same test.
//! Excludes: proving that a syntactically valid digest describes a particular file.

#[cfg(test)]
mod tests {
    use lomo_workspace::SourceFingerprint;

    #[test]
    fn invalid_fingerprints_cannot_bypass_the_parser_via_json() {
        for value in [
            String::new(),
            "a".repeat(63),
            "A".repeat(64),
            "../evidence".to_owned(),
        ] {
            let json =
                serde_json::to_string(&value).unwrap_or_else(|error| panic!("fixture: {error}"));
            let result = serde_json::from_str::<SourceFingerprint>(&json);
            if let Ok(fingerprint) = result {
                panic!("invalid fingerprint escaped validation: {fingerprint:?}");
            }
        }
        let value = SourceFingerprint::of_bytes(b"source bytes");
        let json = serde_json::to_string(&value).unwrap_or_else(|error| panic!("encode: {error}"));
        let decoded: SourceFingerprint =
            serde_json::from_str(&json).unwrap_or_else(|error| panic!("decode: {error}"));
        assert_eq!(decoded, value);
    }
}
