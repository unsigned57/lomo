use syn::{FnArg, ReturnType, Type};

pub(crate) fn is_factory_constructor(method: &syn::ImplItemFn, type_name: &syn::Ident) -> bool {
    FactoryMethodDescriptor::from_method(method, type_name).is_constructor()
}

pub(crate) fn is_result_of_self_type_path(path: &syn::Path, type_name: &syn::Ident) -> bool {
    FactoryReturnShape::from_path(path, type_name).is_result_of_self()
}

pub(crate) fn exported_methods(
    item_impl: &syn::ItemImpl,
) -> impl Iterator<Item = &syn::ImplItemFn> + '_ {
    item_impl
        .items
        .iter()
        .filter_map(|item| match item {
            syn::ImplItem::Fn(method) => Some(method),
            _ => None,
        })
        .filter(|method| matches!(method.vis, syn::Visibility::Public(_)))
        .filter(|method| {
            !method
                .attrs
                .iter()
                .any(|attribute| attribute.path().is_ident("skip"))
        })
}

pub(crate) fn impl_type_name(item_impl: &syn::ItemImpl) -> Option<syn::Ident> {
    match item_impl.self_ty.as_ref() {
        Type::Path(path) => path
            .path
            .segments
            .last()
            .map(|segment| segment.ident.clone()),
        _ => None,
    }
}

enum FactoryMethodDescriptor {
    Constructor,
    NonConstructor,
}

impl FactoryMethodDescriptor {
    fn from_method(method: &syn::ImplItemFn, type_name: &syn::Ident) -> Self {
        let has_receiver = method
            .sig
            .inputs
            .first()
            .is_some_and(|arg| matches!(arg, FnArg::Receiver(_)));

        if has_receiver {
            return Self::NonConstructor;
        }

        if FactoryReturnShape::from_output(&method.sig.output, type_name).is_factory_return() {
            Self::Constructor
        } else {
            Self::NonConstructor
        }
    }

    fn is_constructor(&self) -> bool {
        matches!(self, Self::Constructor)
    }
}

enum FactoryReturnShape {
    SelfValue,
    ResultOfSelf,
    Other,
}

impl FactoryReturnShape {
    fn from_output(output: &ReturnType, type_name: &syn::Ident) -> Self {
        match output {
            ReturnType::Default => Self::Other,
            ReturnType::Type(_, rust_type) => Self::from_type(rust_type, type_name),
        }
    }

    fn from_type(rust_type: &Type, type_name: &syn::Ident) -> Self {
        match rust_type {
            Type::Path(type_path) => Self::from_path(&type_path.path, type_name),
            _ => Self::Other,
        }
    }

    fn from_path(path: &syn::Path, type_name: &syn::Ident) -> Self {
        if Self::is_self_type_path(path, type_name) {
            return Self::SelfValue;
        }

        let Some(result_segment) = path.segments.last() else {
            return Self::Other;
        };
        if result_segment.ident != "Result" {
            return Self::Other;
        }
        let syn::PathArguments::AngleBracketed(arguments) = &result_segment.arguments else {
            return Self::Other;
        };
        let Some(syn::GenericArgument::Type(Type::Path(ok_type_path))) = arguments.args.first()
        else {
            return Self::Other;
        };

        if Self::is_self_type_path(&ok_type_path.path, type_name) {
            Self::ResultOfSelf
        } else {
            Self::Other
        }
    }

    fn is_self_type_path(path: &syn::Path, type_name: &syn::Ident) -> bool {
        path.segments
            .last()
            .is_some_and(|segment| segment.ident == "Self" || segment.ident == *type_name)
    }

    fn is_factory_return(&self) -> bool {
        matches!(self, Self::SelfValue | Self::ResultOfSelf)
    }

    fn is_result_of_self(&self) -> bool {
        matches!(self, Self::ResultOfSelf)
    }
}
