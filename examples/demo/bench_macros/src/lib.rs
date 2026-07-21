use std::mem;

use proc_macro::TokenStream;
use quote::quote;
use syn::ext::IdentExt;
use syn::parse::{Parse, ParseStream};
use syn::{
    Attribute, Error, Fields, Ident, ImplItem, Item, ItemEnum, LitStr, Result, Token,
    parse_macro_input, parse_quote,
};

#[derive(Clone, Copy)]
enum CandidateKind {
    Function,
    Record,
    Enum,
    Object,
    Impl,
    CallbackInterface,
}

enum TargetKind {
    Uniffi,
    WasmBindgen,
}

struct CandidateArgs {
    kind: CandidateKind,
    targets: Vec<TargetKind>,
    constructor_name: Option<Ident>,
}

impl Parse for CandidateArgs {
    fn parse(input: ParseStream<'_>) -> Result<Self> {
        let kind = parse_kind(&input.call(Ident::parse_any)?)?;
        let mut targets = Vec::new();
        let mut constructor_name = None;

        while !input.is_empty() {
            input.parse::<Token![,]>()?;
            let entry = input.call(Ident::parse_any)?;

            if entry == "constructor" {
                input.parse::<Token![=]>()?;
                let constructor = input.parse::<LitStr>()?;
                constructor_name = Some(Ident::new(&constructor.value(), constructor.span()));
                continue;
            }

            targets.push(parse_target(&entry)?);
        }

        if targets.is_empty() {
            return Err(Error::new(
                proc_macro2::Span::call_site(),
                "benchmark_candidate requires at least one target",
            ));
        }

        Ok(Self {
            kind,
            targets,
            constructor_name,
        })
    }
}

#[proc_macro_attribute]
pub fn benchmark_candidate(args: TokenStream, item: TokenStream) -> TokenStream {
    let args = parse_macro_input!(args as CandidateArgs);
    let item = parse_macro_input!(item as Item);

    expand_candidate(args, item)
        .unwrap_or_else(Error::into_compile_error)
        .into()
}

#[proc_macro_attribute]
pub fn demo_case(_args: TokenStream, item: TokenStream) -> TokenStream {
    item
}

fn expand_candidate(args: CandidateArgs, item: Item) -> Result<proc_macro2::TokenStream> {
    match (args.kind, item) {
        (CandidateKind::Function, Item::Fn(mut item_function)) => {
            append_attributes(
                &mut item_function.attrs,
                candidate_attributes(&args.targets, CandidateKind::Function),
            );
            Ok(quote!(#item_function))
        }
        (CandidateKind::Record, Item::Struct(mut item_struct)) => {
            append_attributes(
                &mut item_struct.attrs,
                candidate_attributes(&args.targets, CandidateKind::Record),
            );
            Ok(quote!(#item_struct))
        }
        (CandidateKind::Enum, Item::Enum(mut item_enum)) => {
            append_enum_attributes(&mut item_enum, &args.targets);
            Ok(quote!(#item_enum))
        }
        (CandidateKind::Object, Item::Struct(mut item_struct)) => {
            append_attributes(
                &mut item_struct.attrs,
                candidate_attributes(&args.targets, CandidateKind::Object),
            );
            Ok(quote!(#item_struct))
        }
        (CandidateKind::Impl, Item::Impl(mut item_impl)) => {
            append_attributes(
                &mut item_impl.attrs,
                candidate_attributes(&args.targets, CandidateKind::Impl),
            );

            if let Some(constructor_name) = args.constructor_name {
                let constructor_attributes = constructor_attributes(&args.targets);
                item_impl
                    .items
                    .iter_mut()
                    .filter_map(|impl_item| match impl_item {
                        ImplItem::Fn(method) if method.sig.ident == constructor_name => {
                            Some(method)
                        }
                        _ => None,
                    })
                    .for_each(|method| {
                        append_attributes(&mut method.attrs, constructor_attributes.clone())
                    });
            }

            Ok(quote!(#item_impl))
        }
        (CandidateKind::CallbackInterface, Item::Trait(mut item_trait)) => {
            append_attributes(
                &mut item_trait.attrs,
                candidate_attributes(&args.targets, CandidateKind::CallbackInterface),
            );
            Ok(quote!(#item_trait))
        }
        (kind, item) => Err(Error::new_spanned(
            item,
            format!(
                "benchmark_candidate kind {} does not match item",
                kind_name(&kind)
            ),
        )),
    }
}

fn append_attributes(attributes: &mut Vec<Attribute>, new_attributes: Vec<Attribute>) {
    attributes.extend(new_attributes);
}

fn prepend_attributes(attributes: &mut Vec<Attribute>, new_attributes: Vec<Attribute>) {
    *attributes = new_attributes
        .into_iter()
        .chain(mem::take(attributes))
        .collect();
}

fn append_enum_attributes(item_enum: &mut ItemEnum, targets: &[TargetKind]) {
    let has_payload_variants = item_enum
        .variants
        .iter()
        .any(|variant| !matches!(variant.fields, Fields::Unit));

    let leading_attributes = targets
        .iter()
        .filter(|target| has_payload_variants && matches!(target, TargetKind::Uniffi))
        .flat_map(|target| target_attributes(target, CandidateKind::Enum))
        .collect();
    let trailing_attributes = targets
        .iter()
        .filter(|target| !(has_payload_variants && matches!(target, TargetKind::Uniffi)))
        .flat_map(|target| target_attributes(target, CandidateKind::Enum))
        .collect();

    prepend_attributes(&mut item_enum.attrs, leading_attributes);
    append_attributes(&mut item_enum.attrs, trailing_attributes);
}

fn candidate_attributes(targets: &[TargetKind], kind: CandidateKind) -> Vec<Attribute> {
    targets
        .iter()
        .flat_map(|target| target_attributes(target, kind))
        .collect()
}

fn target_attributes(target: &TargetKind, kind: CandidateKind) -> Vec<Attribute> {
    match (target, kind) {
        (TargetKind::Uniffi, CandidateKind::Function)
        | (TargetKind::Uniffi, CandidateKind::Impl) => {
            vec![parse_quote!(#[cfg_attr(feature = "uniffi", uniffi::export)])]
        }
        (TargetKind::Uniffi, CandidateKind::Record) => {
            vec![parse_quote!(#[cfg_attr(feature = "uniffi", derive(uniffi::Record))])]
        }
        (TargetKind::Uniffi, CandidateKind::Enum) => {
            vec![parse_quote!(#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))])]
        }
        (TargetKind::Uniffi, CandidateKind::Object) => {
            vec![parse_quote!(#[cfg_attr(feature = "uniffi", derive(uniffi::Object))])]
        }
        (TargetKind::Uniffi, CandidateKind::CallbackInterface) => {
            vec![parse_quote!(#[cfg_attr(feature = "uniffi", uniffi::export(callback_interface))])]
        }
        (TargetKind::WasmBindgen, CandidateKind::Function)
        | (TargetKind::WasmBindgen, CandidateKind::Record)
        | (TargetKind::WasmBindgen, CandidateKind::Enum)
        | (TargetKind::WasmBindgen, CandidateKind::Object)
        | (TargetKind::WasmBindgen, CandidateKind::Impl) => {
            vec![
                parse_quote!(#[cfg_attr(feature = "wasm-bench", wasm_bindgen::prelude::wasm_bindgen)]),
            ]
        }
        (TargetKind::WasmBindgen, CandidateKind::CallbackInterface) => Vec::new(),
    }
}

fn constructor_attributes(targets: &[TargetKind]) -> Vec<Attribute> {
    targets
        .iter()
        .flat_map(|target| match target {
            TargetKind::Uniffi => vec![parse_quote!(#[cfg_attr(feature = "uniffi", uniffi::constructor)])],
            TargetKind::WasmBindgen => {
                vec![parse_quote!(#[cfg_attr(feature = "wasm-bench", wasm_bindgen::prelude::wasm_bindgen(constructor))])]
            }
        })
        .collect()
}

fn parse_kind(identifier: &Ident) -> Result<CandidateKind> {
    match identifier.to_string().as_str() {
        "function" => Ok(CandidateKind::Function),
        "record" => Ok(CandidateKind::Record),
        "enum" => Ok(CandidateKind::Enum),
        "object" => Ok(CandidateKind::Object),
        "impl" => Ok(CandidateKind::Impl),
        "callback_interface" => Ok(CandidateKind::CallbackInterface),
        _ => Err(Error::new_spanned(
            identifier,
            "benchmark_candidate kind must be one of function, record, enum, object, impl, callback_interface",
        )),
    }
}

fn parse_target(identifier: &Ident) -> Result<TargetKind> {
    match identifier.to_string().as_str() {
        "uniffi" => Ok(TargetKind::Uniffi),
        "wasm_bindgen" => Ok(TargetKind::WasmBindgen),
        _ => Err(Error::new_spanned(
            identifier,
            "benchmark_candidate target must be one of uniffi or wasm_bindgen",
        )),
    }
}

fn kind_name(kind: &CandidateKind) -> &'static str {
    match kind {
        CandidateKind::Function => "function",
        CandidateKind::Record => "record",
        CandidateKind::Enum => "enum",
        CandidateKind::Object => "object",
        CandidateKind::Impl => "impl",
        CandidateKind::CallbackInterface => "callback_interface",
    }
}
