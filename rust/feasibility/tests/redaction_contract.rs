//! Behavior Contract
//!
//! Capability: phase-0 reports never leak host paths, credentials, or memo bodies.
//!
//! Scenarios:
//! - Given sensitive text, when redacted, then secrets/paths/bodies become placeholders.
//! - Given an absolute path outside the repository, when converted, then conversion fails.
//!
//! Observable outcomes: redacted text contains only placeholders for sensitive tokens;
//! repository-relative conversion succeeds for in-repo paths only.
//! TDD proof: unredacted credentials or absolute host paths fail the contract.
//! Excludes: real credential stores, SAF URI grants, production logging pipelines.

#[cfg(test)]
mod tests {
    use std::path::Path;

    use lomo_feasibility::{RedactionError, redact_sensitive_text, relative_path_for_report};

    #[test]
    fn redacts_credentials_paths_and_bodies() {
        let input = "password=super-secret token=abcdefghijklmnopqrstuvwxyz012345 /home/ephemeral/Projects/lomo/app/src memo_body=hello-world";
        let redacted = redact_sensitive_text(input);
        assert!(!redacted.contains("super-secret"));
        assert!(!redacted.contains("abcdefghijklmnopqrstuvwxyz012345"));
        assert!(!redacted.contains("/home/ephemeral"));
        assert!(!redacted.contains("hello-world"));
        assert!(redacted.contains("[REDACTED_SECRET]"));
        assert!(redacted.contains("[REDACTED_PATH]"));
        assert!(redacted.contains("[REDACTED_BODY]"));
    }

    #[test]
    fn relative_path_conversion_stays_inside_repository() {
        let root = Path::new("/repo");
        let inside = Path::new("/repo/fixtures/markdown/lomo-basic.md");
        assert_eq!(
            relative_path_for_report(root, inside).expect("inside path"),
            "fixtures/markdown/lomo-basic.md"
        );
        let outside = Path::new("/tmp/outside.md");
        assert_eq!(
            relative_path_for_report(root, outside),
            Err(RedactionError::PathEscapesRepository)
        );
    }
}
