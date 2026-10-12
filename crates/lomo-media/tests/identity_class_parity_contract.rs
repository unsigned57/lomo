//! Identity-class ↔ attachment-class parity contract.
//!
//! The identity table (magic + extension) and the workspace destination-class table are
//! two levels of one law: every extension the media owner recognizes must land on the
//! same class the attachment classifier projects. `lomo-media` depends on
//! `lomo-workspace`, so the parity check lives on the side allowed to see both tables.

#[cfg(test)]
mod tests {
    use lomo_media::{ExtensionClass, MediaMime, classify_extension};
    use lomo_workspace::{MediaClass, attachment_media_class};

    fn mime_media_class(mime: MediaMime) -> MediaClass {
        match mime {
            MediaMime::ImagePng
            | MediaMime::ImageJpeg
            | MediaMime::ImageGif
            | MediaMime::ImageWebp
            | MediaMime::ImageBmp
            | MediaMime::ImageHeic
            | MediaMime::ImageHeif
            | MediaMime::ImageAvif => MediaClass::Image,
            MediaMime::AudioM4a
            | MediaMime::AudioMp3
            | MediaMime::AudioAac
            | MediaMime::AudioWav
            | MediaMime::AudioOgg => MediaClass::Audio,
        }
    }

    #[test]
    fn identity_extension_table_agrees_with_attachment_media_class() {
        for ext in [
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "bmp",
            "heic",
            "heif",
            "avif",
            "m4a",
            "mp3",
            "aac",
            "wav",
            "ogg",
            "oga",
            "mp4",
            "m4v",
            "mov",
            "pdf",
            "noext-known",
        ] {
            let from_identity = classify_extension(ext);
            let from_destination = attachment_media_class(&format!("media/x.{ext}"));
            match from_identity {
                Some(ExtensionClass::Mime(mime)) => assert_eq!(
                    from_destination,
                    mime_media_class(mime),
                    "extension {ext} class drifted between identity and destination tables"
                ),
                Some(ExtensionClass::Video) => assert_eq!(
                    from_destination,
                    MediaClass::Video,
                    "extension {ext} class drifted between identity and destination tables"
                ),
                None => assert_eq!(
                    from_destination,
                    MediaClass::File,
                    "extension {ext} must not classify above File without identity support"
                ),
            }
        }
    }
}
