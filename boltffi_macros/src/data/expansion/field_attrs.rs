pub(super) struct BoltffiFieldAttributes;

#[derive(Clone)]
enum BoltffiFieldAttribute {
    Default,
    Extension,
}

impl BoltffiFieldAttributes {
    pub(super) fn strip_from_fields(fields: &mut syn::Fields) {
        fields.iter_mut().for_each(Self::strip_from_field);
    }

    fn strip_from_field(field: &mut syn::Field) {
        field
            .attrs
            .retain(|attribute| BoltffiFieldAttribute::parse(attribute).is_none());
    }
}

impl BoltffiFieldAttribute {
    fn parse(attribute: &syn::Attribute) -> Option<Self> {
        let mut segments = attribute.path().segments.iter();
        let boltffi_segment = segments.next()?;
        let field_attribute_segment = segments.next()?;
        if segments.next().is_some() || boltffi_segment.ident != "boltffi" {
            return None;
        }

        Some(match field_attribute_segment.ident.to_string().as_str() {
            "default" => Self::Default,
            _ => Self::Extension,
        })
    }
}
