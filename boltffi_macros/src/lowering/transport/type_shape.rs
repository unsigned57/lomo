use boltffi_ffi_rules::primitive::Primitive;
use syn::{GenericArgument, PathArguments, PathSegment, Type};

#[derive(Clone, Copy)]
pub(crate) struct TypeDescriptor<'a> {
    rust_type: &'a Type,
}

#[derive(Clone, Copy)]
pub(crate) enum RustTypeShape<'a> {
    Unit,
    Utf8String,
    Primitive(Primitive),
    StandardContainer(StandardContainer<'a>),
    NamedNominal,
    GenericNominal,
    Other,
}

#[derive(Clone, Copy)]
pub(crate) enum StandardContainer<'a> {
    Vec(&'a Type),
    Option(&'a Type),
    Result { ok: &'a Type, err: &'a Type },
}

pub(crate) trait TypeShapeExt {
    fn type_descriptor(&self) -> TypeDescriptor<'_>;
    fn is_primitive_type(&self) -> bool;
    fn is_string_like_type(&self) -> bool;
    fn is_named_nominal_type(&self) -> bool;
    fn is_generic_nominal_type(&self) -> bool;
}

impl<'a> TypeDescriptor<'a> {
    pub(crate) fn new(rust_type: &'a Type) -> Self {
        Self { rust_type }
    }

    pub(crate) fn shape(self) -> RustTypeShape<'a> {
        let normalized_type = self.normalized_type();

        if Self::is_unit_type(normalized_type) {
            return RustTypeShape::Unit;
        }

        if Self::is_owned_string_type(normalized_type) {
            return RustTypeShape::Utf8String;
        }

        if let Some(primitive) = Self::primitive_type(normalized_type) {
            return RustTypeShape::Primitive(primitive);
        }

        if let Some(container) = Self::parse_standard_container(normalized_type) {
            return RustTypeShape::StandardContainer(container);
        }

        if Self::is_named_nominal(normalized_type) {
            return RustTypeShape::NamedNominal;
        }

        if Self::is_generic_nominal(normalized_type) {
            return RustTypeShape::GenericNominal;
        }

        RustTypeShape::Other
    }

    pub(crate) fn primitive(self) -> Option<Primitive> {
        match self.shape() {
            RustTypeShape::Primitive(primitive) => Some(primitive),
            _ => None,
        }
    }

    pub(crate) fn standard_container(self) -> Option<StandardContainer<'a>> {
        Self::parse_standard_container(self.normalized_type())
    }

    fn normalized_type(self) -> &'a Type {
        let mut current_type = self.rust_type;
        loop {
            current_type = match current_type {
                Type::Group(group) => group.elem.as_ref(),
                Type::Paren(paren) => paren.elem.as_ref(),
                _ => return current_type,
            };
        }
    }

    fn is_unit_type(rust_type: &'a Type) -> bool {
        matches!(rust_type, Type::Tuple(tuple) if tuple.elems.is_empty())
    }

    fn is_owned_string_type(rust_type: &'a Type) -> bool {
        Self::last_path_segment(rust_type).is_some_and(|segment| segment.ident == "String")
    }

    fn primitive_type(rust_type: &'a Type) -> Option<Primitive> {
        Self::last_path_segment(rust_type)
            .and_then(|segment| segment.ident.to_string().parse::<Primitive>().ok())
    }

    fn parse_standard_container(rust_type: &'a Type) -> Option<StandardContainer<'a>> {
        let segment = Self::last_path_segment(rust_type)?;
        match segment.ident.to_string().as_str() {
            "Vec" => Self::single_type_argument(segment).map(StandardContainer::Vec),
            "Option" => Self::single_type_argument(segment).map(StandardContainer::Option),
            "Result" => {
                let mut type_arguments = Self::type_arguments(segment);
                let ok_type = type_arguments.next()?;
                let err_type = type_arguments.next()?;
                Some(StandardContainer::Result {
                    ok: ok_type,
                    err: err_type,
                })
            }
            _ => None,
        }
    }

    fn is_named_nominal(rust_type: &'a Type) -> bool {
        let Some(segment) = Self::last_path_segment(rust_type) else {
            return false;
        };

        if !matches!(segment.arguments, PathArguments::None) {
            return false;
        }

        let type_name = segment.ident.to_string();
        (type_name != "()")
            && type_name.parse::<Primitive>().is_err()
            && type_name
                .chars()
                .next()
                .is_some_and(|character| character.is_uppercase())
    }

    fn is_generic_nominal(rust_type: &'a Type) -> bool {
        Self::last_path_segment(rust_type)
            .is_some_and(|segment| matches!(segment.arguments, PathArguments::AngleBracketed(_)))
    }

    fn last_path_segment(rust_type: &'a Type) -> Option<&'a PathSegment> {
        let Type::Path(type_path) = rust_type else {
            return None;
        };

        (type_path.qself.is_none())
            .then_some(type_path.path.segments.last())
            .flatten()
    }

    fn single_type_argument(segment: &'a PathSegment) -> Option<&'a Type> {
        let mut type_arguments = Self::type_arguments(segment);
        let inner_type = type_arguments.next()?;
        type_arguments.next().is_none().then_some(inner_type)
    }

    fn type_arguments(segment: &'a PathSegment) -> impl Iterator<Item = &'a Type> + 'a {
        match &segment.arguments {
            PathArguments::AngleBracketed(arguments) => arguments
                .args
                .iter()
                .filter_map(|argument| match argument {
                    GenericArgument::Type(inner_type) => Some(inner_type),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .into_iter(),
            _ => Vec::new().into_iter(),
        }
    }
}

impl TypeShapeExt for Type {
    fn type_descriptor(&self) -> TypeDescriptor<'_> {
        TypeDescriptor::new(self)
    }

    fn is_primitive_type(&self) -> bool {
        self.type_descriptor().primitive().is_some()
    }

    fn is_string_like_type(&self) -> bool {
        match self.type_descriptor().normalized_type() {
            Type::Reference(reference) => matches!(
                reference.elem.as_ref(),
                Type::Path(path)
                    if path
                        .path
                        .segments
                        .last()
                        .is_some_and(|segment| segment.ident == "str")
            ),
            rust_type => matches!(
                TypeDescriptor::new(rust_type).shape(),
                RustTypeShape::Utf8String
            ),
        }
    }

    fn is_named_nominal_type(&self) -> bool {
        TypeDescriptor::is_named_nominal(self.type_descriptor().normalized_type())
    }

    fn is_generic_nominal_type(&self) -> bool {
        TypeDescriptor::is_generic_nominal(self.type_descriptor().normalized_type())
    }
}
