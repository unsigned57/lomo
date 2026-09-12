/*
 * Behavior Contract:
 * - Unit under test: parse_channel and rust-toolchain.toml assignment rewriting.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: the repository pin is a concrete x.y or x.y.z channel, never a floating name.
 *
 * Scenarios:
 * - Given floating channels, when parsed, then each name is rejected.
 * - Given minor and patch pins, when parsed, then channel and MSRV are recorded.
 * - Given a rustc version line, when matched, then only the pinned minor family is accepted.
 * - Given a toolchain toml snippet, when the channel is replaced, then other keys stay intact.
 *
 * Observable outcomes:
 * - Parse errors, RustPin fields, version-line matches, and rewritten toml text.
 *
 * TDD proof:
 * - Relocated from crates/lomo-xtask/src/rust_pin.rs to keep production sources test-free.
 *
 * Excludes:
 * - rustup network installs.
 */

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing pin facts"
)]
mod tests {
    use lomo_xtask::{parse_channel, replace_toml_assignment};

    #[test]
    fn parse_rejects_floating_channels() {
        for name in ["stable", "nightly", "beta", "nightly-2026-01-01"] {
            assert!(parse_channel(name).is_err(), "{name}");
        }
    }

    #[test]
    fn parse_accepts_minor_and_patch() {
        let minor = parse_channel("1.96").expect("minor");
        assert_eq!(minor.channel, "1.96");
        assert_eq!(minor.msrv, "1.96");
        let patch = parse_channel("1.96.1").expect("patch");
        assert_eq!(patch.channel, "1.96.1");
        assert_eq!(patch.msrv, "1.96");
    }

    #[test]
    fn rustc_line_matches_minor_pin() {
        let pin = parse_channel("1.96").expect("minor pin");
        assert!(pin.matches_rustc_version_line("rustc 1.96.1 (31fca3adb 2026-06-26)"));
        assert!(!pin.matches_rustc_version_line("rustc 1.97.0 (deadbeef 2026-07-01)"));
    }

    #[test]
    fn replace_toml_channel() {
        let text = "[toolchain]\nchannel = \"1.96\"\nprofile = \"minimal\"\n";
        let next = replace_toml_assignment(text, "channel", "1.97").expect("rewrite");
        assert!(next.contains("channel = \"1.97\""));
        assert!(next.contains("profile = \"minimal\""));
    }
}
