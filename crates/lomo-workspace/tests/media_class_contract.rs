//! Contract: attachment media classification is a workspace-owned fact (B-05/P-24, R-07).
//!
//! - Unit under test: `lomo_workspace::attachment_media_class`, `MediaClass`,
//!   `is_audio_attachment_destination`.
//! - Capability: every Markdown attachment destination projects exactly one owner media class
//!   (`Image` | `Video` | `Audio` | `File`); no consumer may re-derive a class from MIME
//!   prefixes or private extension tables.
//! - Given destinations across the owner extension table, when classified, then image, audio
//!   (including `.oga`), and video containers land on their class while unknown, extensionless,
//!   or non-media destinations land on `File`.
//! - Given a `data:` destination, when classified, then the declared media type decides the
//!   class instead of a file extension.
//! - Given the audio predicate kept for link-collection call sites, when the owner class is
//!   `Audio`, then it agrees with `attachment_media_class` on every destination.
//!
//! Observable outcomes: typed `MediaClass` values per destination; audio predicate parity.
//! TDD proof: RED before the enum/table existed (no owner classifier above the audio
//! predicate); GREEN with the classifier as the single extension-table authority.
//! Excludes: magic-byte/mime identity (lomo-media), Kotlin transport shape (lomo-native).

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use lomo_workspace::{MediaClass, attachment_media_class, is_audio_attachment_destination};

    #[test]
    fn image_extensions_classify_as_image() {
        for destination in [
            "media/pic.png",
            "media/pic.jpg",
            "media/pic.jpeg",
            "media/pic.gif",
            "media/pic.webp",
            "media/pic.bmp",
            "media/pic.heic",
            "media/pic.heif",
            "media/pic.avif",
            "media/PIC.PNG",
        ] {
            assert_eq!(
                attachment_media_class(destination),
                MediaClass::Image,
                "{destination}"
            );
        }
    }

    #[test]
    fn audio_extensions_classify_as_audio_including_oga() {
        for destination in [
            "media/voice.m4a",
            "media/voice.mp3",
            "media/voice.aac",
            "media/voice.wav",
            "media/voice.ogg",
            "media/voice.oga",
            "media/VOICE.M4A",
        ] {
            assert_eq!(
                attachment_media_class(destination),
                MediaClass::Audio,
                "{destination}"
            );
        }
    }

    #[test]
    fn video_extensions_classify_as_video() {
        for destination in [
            "media/clip.mp4",
            "media/clip.m4v",
            "media/clip.mov",
            "https://cdn.example/clip.mp4",
        ] {
            assert_eq!(
                attachment_media_class(destination),
                MediaClass::Video,
                "{destination}"
            );
        }
    }

    #[test]
    fn unknown_extensionless_and_non_media_destinations_classify_as_file() {
        for destination in [
            "media/notes.pdf",
            "media/archive.zip",
            "media/no-extension",
            "media/.hidden",
            "#anchor",
            "https://lomo.app/page",
            "",
        ] {
            assert_eq!(
                attachment_media_class(destination),
                MediaClass::File,
                "{destination}"
            );
        }
    }

    #[test]
    fn data_scheme_destinations_classify_by_declared_media_type() {
        assert_eq!(
            attachment_media_class("data:image/png;base64,iVBOR"),
            MediaClass::Image
        );
        assert_eq!(
            attachment_media_class("data:audio/ogg;base64,T2d"),
            MediaClass::Audio
        );
        assert_eq!(
            attachment_media_class("data:video/mp4;base64,AAAA"),
            MediaClass::Video
        );
        assert_eq!(
            attachment_media_class("data:text/plain;base64,SGk="),
            MediaClass::File
        );
    }

    #[test]
    fn audio_predicate_agrees_with_owner_class_on_every_probe() {
        for destination in [
            "media/voice.oga",
            "media/voice.mp3",
            "media/clip.mp4",
            "media/pic.png",
            "media/notes.pdf",
            "media/no-extension",
        ] {
            assert_eq!(
                is_audio_attachment_destination(destination),
                attachment_media_class(destination) == MediaClass::Audio,
                "{destination}"
            );
        }
    }
}
