use proc_macro2::{Delimiter, Span, TokenStream, TokenTree};
use syn::parse::Parser;
use syn::punctuated::Punctuated;
use syn::spanned::Spanned;
use syn::visit::{self, Visit};
use syn::{Attribute, Expr, Lit, Meta, Token};

use super::Violation;

pub fn rust_source_violations(path: &str, source: &str) -> Result<Vec<Violation>, String> {
    let file =
        syn::parse_file(source).map_err(|error| format!("{path}: invalid Rust syntax: {error}"))?;
    let mut visitor = SourcePolicy {
        path,
        violations: Vec::new(),
    };
    visitor.visit_file(&file);
    Ok(visitor.violations)
}

struct SourcePolicy<'a> {
    path: &'a str,
    violations: Vec<Violation>,
}

impl SourcePolicy<'_> {
    fn record(&mut self, rule: &'static str, span: Span, detail: impl Into<String>) {
        self.violations.push(Violation::new(
            rule,
            format!("{}:{}", self.path, span.start().line),
            detail,
        ));
    }

    fn inspect_meta(&mut self, meta: &Meta) {
        let path = meta.path();
        if path.is_ident("allow") || path.is_ident("warn") {
            self.record(
                "rust-source-lint-override",
                meta.span(),
                "source cannot lower workspace lint policy",
            );
        } else if path.is_ident("test") {
            self.record(
                "rust-tests-in-production",
                meta.span(),
                "move tests from src/ into the owning tests/ target",
            );
        } else if path.is_ident("cfg") {
            match contains_test_cfg(meta) {
                Ok(true) => self.record(
                    "rust-tests-in-production",
                    meta.span(),
                    "move tests from src/ into the owning tests/ target",
                ),
                Ok(false) => {}
                Err(error) => self.record("rust-unparsed-policy-attribute", meta.span(), error),
            }
        } else if path.is_ident("expect") {
            self.inspect_expect(meta);
        } else if path.is_ident("cfg_attr") {
            match meta_arguments(meta) {
                Ok(arguments) => {
                    for argument in arguments.iter().skip(1) {
                        self.inspect_meta(argument);
                    }
                }
                Err(error) => self.record("rust-unparsed-policy-attribute", meta.span(), error),
            }
        }
    }

    fn inspect_expect(&mut self, meta: &Meta) {
        let arguments = match meta_arguments(meta) {
            Ok(arguments) => arguments,
            Err(error) => {
                self.record("rust-unparsed-policy-attribute", meta.span(), error);
                return;
            }
        };
        let has_reason = arguments.iter().any(|argument| {
            matches!(argument, Meta::NameValue(value) if value.path.is_ident("reason") &&
                matches!(&value.value, Expr::Lit(expression) if matches!(&expression.lit, Lit::Str(reason) if !reason.value().trim().is_empty())))
        });
        if !has_reason {
            self.record(
                "rust-expect-reason",
                meta.span(),
                "lint expectations require a nonempty reason",
            );
        }
        for argument in arguments {
            if let Meta::Path(path) = argument {
                let name = path
                    .segments
                    .iter()
                    .map(|segment| segment.ident.to_string())
                    .collect::<Vec<_>>()
                    .join("::");
                if protected_lint(&name) {
                    self.record(
                        "rust-protected-lint",
                        path.span(),
                        format!("{name} cannot be suppressed with expect"),
                    );
                }
            }
        }
    }

    fn inspect_macro_tokens(&mut self, tokens: TokenStream) {
        let mut tokens = tokens.into_iter().peekable();
        while let Some(token) = tokens.next() {
            match token {
                TokenTree::Ident(ident) if ident == "unsafe" => {
                    self.record(
                        "rust-first-party-unsafe",
                        ident.span(),
                        "macro templates cannot mint unsafe code",
                    );
                }
                TokenTree::Punct(punct) if punct.as_char() == '#' => {
                    let inner = matches!(tokens.peek(), Some(TokenTree::Punct(punct)) if punct.as_char() == '!');
                    let mut attribute = TokenStream::from(TokenTree::Punct(punct));
                    if inner && let Some(bang) = tokens.next() {
                        attribute.extend([bang]);
                    }
                    if matches!(tokens.peek(), Some(TokenTree::Group(group)) if group.delimiter() == Delimiter::Bracket)
                        && let Some(group) = tokens.next()
                    {
                        attribute.extend([group]);
                        self.inspect_macro_attribute(attribute, inner);
                    }
                }
                TokenTree::Group(group) => self.inspect_macro_tokens(group.stream()),
                TokenTree::Ident(_) | TokenTree::Punct(_) | TokenTree::Literal(_) => {}
            }
        }
    }

    fn inspect_macro_attribute(&mut self, attribute: TokenStream, inner: bool) {
        let parser = if inner {
            Attribute::parse_inner
        } else {
            Attribute::parse_outer
        };
        match parser.parse2(attribute) {
            Ok(attributes) => {
                for attribute in attributes {
                    self.inspect_meta(&attribute.meta);
                }
            }
            Err(error) => self.record(
                "rust-unparsed-policy-attribute",
                error.span(),
                error.to_string(),
            ),
        }
    }
}

impl<'ast> Visit<'ast> for SourcePolicy<'_> {
    fn visit_attribute(&mut self, attribute: &'ast Attribute) {
        self.inspect_meta(&attribute.meta);
    }

    fn visit_item_mod(&mut self, module: &'ast syn::ItemMod) {
        for attribute in &module.attrs {
            if !attribute.path().is_ident("cfg") {
                continue;
            }
            match contains_test_cfg(&attribute.meta) {
                Ok(true) => {
                    self.record(
                        "rust-tests-in-production",
                        module.span(),
                        "move the test module into tests/",
                    );
                    return;
                }
                Err(error) => {
                    self.record("rust-unparsed-policy-attribute", attribute.span(), error);
                    return;
                }
                Ok(false) => {}
            }
        }
        visit::visit_item_mod(self, module);
    }

    fn visit_signature(&mut self, signature: &'ast syn::Signature) {
        if let Some(unsafe_token) = signature.unsafety {
            self.record(
                "rust-first-party-unsafe",
                unsafe_token.span(),
                "first-party unsafe functions are forbidden",
            );
        }
        visit::visit_signature(self, signature);
    }

    fn visit_expr_unsafe(&mut self, expression: &'ast syn::ExprUnsafe) {
        self.record(
            "rust-first-party-unsafe",
            expression.span(),
            "first-party unsafe blocks are forbidden",
        );
        visit::visit_expr_unsafe(self, expression);
    }

    fn visit_item_impl(&mut self, item: &'ast syn::ItemImpl) {
        if let Some(unsafe_token) = item.unsafety {
            self.record(
                "rust-first-party-unsafe",
                unsafe_token.span(),
                "first-party unsafe implementations are forbidden",
            );
        }
        visit::visit_item_impl(self, item);
    }

    fn visit_item_trait(&mut self, item: &'ast syn::ItemTrait) {
        if let Some(unsafe_token) = item.unsafety {
            self.record(
                "rust-first-party-unsafe",
                unsafe_token.span(),
                "first-party unsafe traits are forbidden",
            );
        }
        visit::visit_item_trait(self, item);
    }

    fn visit_item_foreign_mod(&mut self, item: &'ast syn::ItemForeignMod) {
        if let Some(unsafe_token) = item.unsafety {
            self.record(
                "rust-first-party-unsafe",
                unsafe_token.span(),
                "first-party unsafe extern blocks are forbidden",
            );
        }
        visit::visit_item_foreign_mod(self, item);
    }

    fn visit_macro(&mut self, item: &'ast syn::Macro) {
        self.inspect_macro_tokens(item.tokens.clone());
    }
}

pub(super) fn meta_arguments(meta: &Meta) -> Result<Punctuated<Meta, Token![,]>, String> {
    let Meta::List(list) = meta else {
        return Err("expected policy attribute arguments".to_owned());
    };
    Punctuated::<Meta, Token![,]>::parse_terminated
        .parse2(list.tokens.clone())
        .map_err(|error| error.to_string())
}

fn contains_test_cfg(meta: &Meta) -> Result<bool, String> {
    match meta {
        Meta::Path(path) => Ok(path.is_ident("test")),
        Meta::List(_) => {
            for argument in meta_arguments(meta)? {
                if contains_test_cfg(&argument)? {
                    return Ok(true);
                }
            }
            Ok(false)
        }
        Meta::NameValue(_) => Ok(false),
    }
}

fn protected_lint(name: &str) -> bool {
    matches!(
        name,
        "unsafe_code"
            | "warnings"
            | "unused_must_use"
            | "clippy::all"
            | "clippy::pedantic"
            | "clippy::nursery"
            | "clippy::correctness"
            | "clippy::suspicious"
            | "clippy::perf"
            | "clippy::style"
            | "clippy::allow_attributes"
            | "clippy::allow_attributes_without_reason"
            | "clippy::unwrap_used"
            | "clippy::expect_used"
            | "clippy::let_underscore_must_use"
            | "clippy::let_underscore_untyped"
            | "clippy::unused_result_ok"
            | "clippy::map_err_ignore"
            | "clippy::mem_forget"
    )
}
