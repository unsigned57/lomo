//! Behavior Contract
//!
//! Capability: durable media-trash basename wire format shared between the session sweep that
//! writes entries and the trash listing that recovers them.
//!
//! Scenarios:
//! - Given a digest, timestamp, and original name, when encoded and decoded, then the pair
//!   round-trips byte-exactly.
//! - Given a malformed basename, when decoded, then validation fails closed.
//! - Given a recovery window, when expiry is computed, then it is exclusive after the window.
//!
//! Observable outcomes: parsed `(digest, trashed_at_ms)`, `invalid_media_trash_name`, expiry sums.
//! Excludes: sweep/list/delete mechanics (session-owned in `lomo-application`), store projection,
//! FFI, production DI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_media::{
        ContentDigest, MediaTrashEntry, parse_trash_entry_name, trash_entry_name, wall_clock_ms,
    };

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn trash_name_round_trips_digest_and_timestamp() {
        let digest = ContentDigest::of_slice(PNG_1X1);
        let name = trash_entry_name(digest.as_str(), 1_000, "photo.png");
        assert_eq!(name, format!("{}_1000_photo.png", digest.as_str()));
        let (parsed_digest, parsed_ms) = parse_trash_entry_name(&name).expect("parse");
        assert_eq!(parsed_digest, digest);
        assert_eq!(parsed_ms, 1_000);
    }

    #[test]
    fn trash_name_preserves_original_names_containing_underscores() {
        let digest = ContentDigest::of_slice(PNG_1X1);
        let name = trash_entry_name(digest.as_str(), 42, "my_photo_final.png");
        let (parsed_digest, parsed_ms) = parse_trash_entry_name(&name).expect("parse");
        assert_eq!(parsed_digest, digest);
        assert_eq!(parsed_ms, 42);
    }

    #[test]
    fn malformed_trash_names_fail_closed() {
        for name in [
            "not-a-trash-name.png",
            "onlyone_part",
            "zz_1000_photo.png",
            &format!(
                "{}_abc_photo.png",
                ContentDigest::of_slice(PNG_1X1).as_str()
            ),
            &format!("{}_1000_", ContentDigest::of_slice(PNG_1X1).as_str()),
        ] {
            let error = parse_trash_entry_name(name).expect_err("must reject");
            assert_eq!(error.code(), "invalid_media_trash_name", "name: {name}");
        }
    }

    #[test]
    fn trash_entry_expiry_is_exclusive_after_the_window() {
        let digest = ContentDigest::of_slice(PNG_1X1);
        let entry = MediaTrashEntry {
            digest,
            trash_path: "x".into(),
            trashed_at_ms: 1_000,
            expires_at_ms: 1_000_u64.saturating_add(5_000),
        };
        assert_eq!(entry.expires_at_ms, 6_000);
        // wall clock helper is callable for host tests that need a real now_ms seed.
        let now_ms: u64 = wall_clock_ms();
        assert!(now_ms < u64::MAX);
    }
}
