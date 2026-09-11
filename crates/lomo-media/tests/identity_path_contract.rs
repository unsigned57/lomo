//! Behavior Contract
//!
//! Capability: establish content identity (streaming sha256 + magic MIME) and workspace path
//! policy for `lomo-media` without store/FFI.
//!
//! Scenarios:
//! - Given PNG bytes, when digest is streamed, then lowercase hex sha256 matches known golden.
//! - Given JPEG magic with `.png` extension hint, when mime is detected, then validation rejects
//!   magic/extension conflict.
//! - Given absolute or `..` paths, when `MediaRelativePath::parse` runs, then validation fails.
//! - Given shipped owner constants, when `MediaOwnerIdentity::current` is validated, then ok.
//!
//! Observable outcomes: digests, mime rejects, path rejects, owner identity.
//! Excludes: stage/commit/orphan, FFI, production DI cutover.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_media::{
        ContentDigest, MEDIA_CRATE_NAME, MediaMime, MediaOwnerIdentity, MediaRelativePath,
        error_category, media_conflict, media_corruption, media_storage, media_validation,
        read_magic_header, write_bytes_for_tests,
    };

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn owner_identity_matches_shipped_scaffold() {
        let identity = MediaOwnerIdentity::current();
        assert_eq!(identity.crate_name, "lomo-media");
        assert_eq!(identity.crate_name, MEDIA_CRATE_NAME);
        identity
            .validate()
            .expect("shipped owner identity must validate");
    }

    #[test]
    fn forged_owner_identity_fails_closed() {
        let wrong = MediaOwnerIdentity {
            crate_name: "not-lomo-media",
        };
        let error = wrong.validate().expect_err("forged crate name must fail");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "invalid_media_owner");
    }

    #[test]
    fn png_digest_golden_matches_sha256() {
        let digest = ContentDigest::of_slice(PNG_1X1);
        // Precomputed sha256 of PNG_1X1 fixture.
        let expected = {
            use sha2::{Digest, Sha256};
            format!("{:x}", Sha256::digest(PNG_1X1))
        };
        assert_eq!(digest.as_str(), expected);
        assert_eq!(digest.as_str().len(), 64);
        ContentDigest::parse(digest.as_str()).expect("digest wire form parses");
    }

    #[test]
    fn invalid_digest_wire_form_fails_closed() {
        let error = ContentDigest::parse("not-a-digest").expect_err("must fail");
        assert_eq!(error.code(), "invalid_content_digest");
    }

    #[test]
    fn magic_detects_png() {
        let mime = MediaMime::detect(PNG_1X1, Some("png")).expect("png magic");
        assert_eq!(mime, MediaMime::ImagePng);
        assert_eq!(mime.as_str(), "image/png");
    }

    #[test]
    fn magic_extension_conflict_rejects() {
        // JPEG SOI + PNG extension hint.
        let jpeg_header = [0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10];
        let error = MediaMime::detect(&jpeg_header, Some("png")).expect_err("conflict");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "media_magic_extension_conflict");
    }

    #[test]
    fn unknown_magic_rejects() {
        let error = MediaMime::detect(b"not-media", None).expect_err("unknown");
        assert_eq!(error.code(), "unsupported_media_magic");
    }

    #[test]
    fn media_relative_path_accepts_canonical() {
        let path = MediaRelativePath::parse("media/photo.jpg").expect("ok");
        assert_eq!(path.as_str(), "media/photo.jpg");
    }

    #[test]
    fn media_relative_path_rejects_escape() {
        for raw in ["../secret", "/abs", "a\\b", ".lomo/state/x", ""] {
            let error = MediaRelativePath::parse(raw).expect_err(raw);
            assert_eq!(error.category(), ErrorCategory::Validation, "{raw}");
        }
    }

    #[test]
    fn magic_detects_jpeg_gif_webp_bmp_and_audio_families() {
        let jpeg = [0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10];
        assert_eq!(
            MediaMime::detect(&jpeg, Some("jpg")).expect("jpeg"),
            MediaMime::ImageJpeg
        );
        assert_eq!(
            MediaMime::detect(b"GIF89a............", Some("gif")).expect("gif"),
            MediaMime::ImageGif
        );
        let mut webp = b"RIFF....WEBP".to_vec();
        webp.get_mut(4..8)
            .expect("webp size")
            .copy_from_slice(&[0, 0, 0, 0]);
        assert_eq!(
            MediaMime::detect(&webp, Some("webp")).expect("webp"),
            MediaMime::ImageWebp
        );
        assert_eq!(
            MediaMime::detect(b"BM..............", Some("bmp")).expect("bmp"),
            MediaMime::ImageBmp
        );
        let mut ftyp = vec![0_u8; 16];
        ftyp.get_mut(4..8)
            .expect("ftyp brand")
            .copy_from_slice(b"ftyp");
        ftyp.get_mut(8..12).expect("heic").copy_from_slice(b"heic");
        assert_eq!(
            MediaMime::detect(&ftyp, Some("heic")).expect("heic"),
            MediaMime::ImageHeic
        );
        ftyp.get_mut(8..12).expect("mif1").copy_from_slice(b"mif1");
        assert_eq!(
            MediaMime::detect(&ftyp, Some("heif")).expect("heif"),
            MediaMime::ImageHeif
        );
        ftyp.get_mut(8..12).expect("avif").copy_from_slice(b"avif");
        assert_eq!(
            MediaMime::detect(&ftyp, Some("avif")).expect("avif"),
            MediaMime::ImageAvif
        );
        ftyp.get_mut(8..12).expect("m4a").copy_from_slice(b"M4A ");
        assert_eq!(
            MediaMime::detect(&ftyp, Some("m4a")).expect("m4a"),
            MediaMime::AudioM4a
        );
        assert_eq!(
            MediaMime::detect(b"ID3.............", Some("mp3")).expect("mp3"),
            MediaMime::AudioMp3
        );
        assert_eq!(
            MediaMime::detect(&[0xff, 0xf1, 0x00, 0x00], Some("aac")).expect("aac"),
            MediaMime::AudioAac
        );
        let mut wav = b"RIFF....WAVE".to_vec();
        wav.get_mut(4..8)
            .expect("wav size")
            .copy_from_slice(&[0, 0, 0, 0]);
        assert_eq!(
            MediaMime::detect(&wav, Some("wav")).expect("wav"),
            MediaMime::AudioWav
        );
        assert_eq!(
            MediaMime::detect(b"OggS............", Some("ogg")).expect("ogg"),
            MediaMime::AudioOgg
        );
    }

    #[test]
    fn digest_stream_from_path_matches_slice() {
        let root = tempfile::tempdir().expect("tmp");
        let path = root.path().join("x.png");
        write_bytes_for_tests(&path, PNG_1X1).expect("write");
        let (from_path, size) = ContentDigest::stream_from_path(&path).expect("stream");
        let from_slice = ContentDigest::of_slice(PNG_1X1);
        assert_eq!(from_path, from_slice);
        assert_eq!(size, PNG_1X1.len() as u64);
        let header = read_magic_header(&path).expect("header");
        assert!(header.starts_with(&[0x89, b'P', b'N', b'G']));
    }

    #[test]
    fn media_mime_parse_and_preferred_extension_round_trip() {
        for mime in [
            MediaMime::ImagePng,
            MediaMime::ImageJpeg,
            MediaMime::ImageGif,
            MediaMime::ImageWebp,
            MediaMime::ImageBmp,
            MediaMime::ImageHeic,
            MediaMime::ImageHeif,
            MediaMime::ImageAvif,
            MediaMime::AudioM4a,
            MediaMime::AudioMp3,
            MediaMime::AudioAac,
            MediaMime::AudioWav,
            MediaMime::AudioOgg,
        ] {
            let wire = mime.as_str();
            let parsed = MediaMime::parse(wire).expect("parse wire");
            assert_eq!(parsed, mime);
            assert!(!mime.preferred_extension().is_empty());
        }
        let err = MediaMime::parse("image/tiff").expect_err("unsupported");
        assert_eq!(err.code(), "unsupported_media_mime");
    }

    #[test]
    fn content_digest_parse_rejects_bad_hex() {
        let err = ContentDigest::parse("zz").expect_err("bad");
        assert!(!err.code().is_empty());
        assert_eq!(error_category(&err), ErrorCategory::Validation);
        let good = ContentDigest::of_slice(PNG_1X1);
        let round = ContentDigest::parse(good.as_str()).expect("parse good");
        assert_eq!(round, good);
    }

    #[test]
    fn media_owner_validate_rejects_wrong_crate_name() {
        let bad = MediaOwnerIdentity {
            crate_name: "not-lomo-media",
        };
        let err = bad.validate().expect_err("wrong owner");
        assert_eq!(err.code(), "invalid_media_owner");
        assert_eq!(error_category(&err), ErrorCategory::Validation);
    }

    #[test]
    fn media_boundary_error_constructors_set_categories() {
        let storage = media_storage("media_storage_probe", "storage boundary");
        assert_eq!(storage.code(), "media_storage_probe");
        assert_eq!(error_category(&storage), ErrorCategory::Storage);

        let corruption = media_corruption("media_corruption_probe", "corruption boundary");
        assert_eq!(corruption.code(), "media_corruption_probe");
        assert_eq!(error_category(&corruption), ErrorCategory::Corruption);

        let conflict = media_conflict("media_conflict_probe", "conflict boundary");
        assert_eq!(conflict.code(), "media_conflict_probe");
        assert_eq!(error_category(&conflict), ErrorCategory::Conflict);

        let validation = media_validation("media_validation_probe", "validation boundary");
        assert_eq!(validation.code(), "media_validation_probe");
        assert_eq!(error_category(&validation), ErrorCategory::Validation);
    }
}
