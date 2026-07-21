use boltffi_ast::Visibility;

pub(super) fn kind(visibility: &syn::Visibility) -> Visibility {
    match visibility {
        syn::Visibility::Public(_) => Visibility::Public,
        syn::Visibility::Restricted(restricted) => {
            Visibility::Restricted(crate::spelling::path(&restricted.path))
        }
        syn::Visibility::Inherited => Visibility::Private,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn visibility(source: &str) -> syn::Visibility {
        syn::parse_str(source).expect("valid visibility")
    }

    #[test]
    fn scans_public_visibility() {
        assert_eq!(kind(&visibility("pub")), Visibility::Public);
    }

    #[test]
    fn scans_private_visibility() {
        assert_eq!(kind(&syn::Visibility::Inherited), Visibility::Private);
    }

    #[test]
    fn scans_restricted_visibility() {
        assert_eq!(
            kind(&visibility("pub(crate)")),
            Visibility::Restricted("crate".to_owned())
        );
        assert_eq!(
            kind(&visibility("pub(super)")),
            Visibility::Restricted("super".to_owned())
        );
        assert_eq!(
            kind(&visibility("pub(in crate::ffi)")),
            Visibility::Restricted("crate::ffi".to_owned())
        );
    }
}
