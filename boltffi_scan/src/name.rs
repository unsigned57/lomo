use boltffi_ast::{CanonicalName, NamePart, SourceName};

pub(super) fn source(ident: &syn::Ident) -> SourceName {
    SourceName::new(ident.to_string(), canonical(ident))
}

pub(super) fn source_segment(segment: &str) -> SourceName {
    SourceName::new(segment, canonical_segment(segment))
}

pub(super) fn canonical(ident: &syn::Ident) -> CanonicalName {
    canonical_segment(&ident_source(ident))
}

pub(super) fn canonical_segment(segment: &str) -> CanonicalName {
    CanonicalName::new(
        snake_case(segment)
            .split('_')
            .filter(|part| !part.is_empty())
            .map(NamePart::new)
            .collect(),
    )
}

pub(super) fn symbol_segment(segment: &str) -> String {
    snake_case(segment)
}

fn ident_source(ident: &syn::Ident) -> String {
    ident
        .to_string()
        .strip_prefix("r#")
        .map_or_else(|| ident.to_string(), ToOwned::to_owned)
}

fn snake_case(name: &str) -> String {
    let characters = name.chars().collect::<Vec<_>>();
    characters.iter().enumerate().fold(
        String::with_capacity(name.len()),
        |mut normalized, (index, character)| {
            if *character == '_' {
                if !normalized.is_empty() && !normalized.ends_with('_') {
                    normalized.push('_');
                }
                return normalized;
            }

            if character.is_uppercase() && index > 0 {
                let previous = characters[index - 1];
                let next = characters.get(index + 1).copied();
                let previous_is_word = previous.is_lowercase() || previous.is_ascii_digit();
                let acronym_boundary =
                    previous.is_uppercase() && next.is_some_and(char::is_lowercase);

                if (previous_is_word || acronym_boundary) && !normalized.ends_with('_') {
                    normalized.push('_');
                }
            }

            normalized.extend(character.to_lowercase());
            normalized
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ident(source: &str) -> syn::Ident {
        syn::parse_str(source).expect("valid identifier")
    }

    fn name(parts: &[&str]) -> CanonicalName {
        CanonicalName::new(parts.iter().copied().map(NamePart::new).collect())
    }

    #[test]
    fn splits_snake_case_identifier() {
        assert_eq!(
            canonical(&ident("make_handler")),
            name(&["make", "handler"])
        );
        assert_eq!(
            canonical(&ident("make__handler")),
            name(&["make", "handler"])
        );
        assert_eq!(
            canonical(&ident("_make_handler_")),
            name(&["make", "handler"])
        );
    }

    #[test]
    fn splits_acronym_identifier() {
        assert_eq!(canonical(&ident("HTTPRequest")), name(&["http", "request"]));
        assert_eq!(
            canonical(&ident("HTTPServerURL")),
            name(&["http", "server", "url"])
        );
        assert_eq!(canonical(&ident("URL")), CanonicalName::single("url"));
    }

    #[test]
    fn strips_raw_identifier_prefix() {
        assert_eq!(canonical(&ident("r#type")), CanonicalName::single("type"));
        assert_eq!(
            canonical(&ident("r#async_handler")),
            name(&["async", "handler"])
        );
    }

    #[test]
    fn keeps_single_word_identifier_as_one_part() {
        assert_eq!(canonical(&ident("Point")), CanonicalName::single("point"));
    }

    #[test]
    fn keeps_digits_attached_to_their_word_part() {
        assert_eq!(canonical(&ident("Point2D")), name(&["point2", "d"]));
        assert_eq!(canonical(&ident("Vector3")), name(&["vector3"]));
        assert_eq!(canonical(&ident("user2_id")), name(&["user2", "id"]));
    }

    #[test]
    fn source_name_preserves_spelling_and_canonical_projection() {
        let http_request = source(&ident("HTTPRequest"));
        assert_eq!(http_request.spelling(), "HTTPRequest");
        assert_eq!(http_request.canonical(), &name(&["http", "request"]));

        let raw_type = source(&ident("r#type"));
        assert_eq!(raw_type.spelling(), "r#type");
        assert_eq!(raw_type.canonical(), &CanonicalName::single("type"));
    }
}
