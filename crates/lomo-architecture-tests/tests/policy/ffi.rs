use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use lomo_xtask::kotlin_code_view;
use sha2::{Digest as _, Sha256};
use syn::{Item, Meta, Visibility, punctuated::Punctuated, visit::Visit};

use super::Violation;

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct Export {
    pub rust: String,
    pub kotlin: String,
    pub callback: bool,
}

/// The full compile surface of one parsed file. rustc honors `#[export]` /
/// `#[no_mangle]` markers and expands statement-position macros to items on
/// *two* surfaces: crate/module item position, and the inner surface inside
/// `fn` bodies, member bodies and `const`/`static` initializers. `inner`
/// records which surface the visitor is on — the inner surface is descended
/// like any other (nested items can carry their own bodies), but no export
/// obligation can be minted there: every marker is rejected instead.
struct Scan {
    exports: BTreeSet<Export>,
    inner: bool,
    error: Option<String>,
}

/// Built-in expression/statement macros whose expansion is compiler-specified
/// and can never splice items — the only macro invocations allowed inside
/// scanned bodies. A `macro_rules!` or proc-macro call expands to a block that
/// may hold `#[export]`/`#[no_mangle]` items (rustc's second item surface), so
/// anything else fails closed. `thread_local!`/`include!` deliberately stay
/// out: both emit items by definition.
const EXPRESSION_MACROS: &[&str] = &[
    "assert",
    "assert_eq",
    "assert_ne",
    "cfg",
    "column",
    "compile_error",
    "concat",
    "dbg",
    "debug_assert",
    "debug_assert_eq",
    "debug_assert_ne",
    "env",
    "eprint",
    "eprintln",
    "file",
    "format",
    "format_args",
    "format_args_nl",
    "include_bytes",
    "include_str",
    "line",
    "matches",
    "module_path",
    "option_env",
    "panic",
    "print",
    "println",
    "stringify",
    "todo",
    "unimplemented",
    "unreachable",
    "vec",
    "write",
    "writeln",
];

/// `path` names a compiler-built-in expression/statement macro: bare
/// `format!`/`panic!` or the same macro reached through the `std`/`core`/
/// `alloc` roots (`::core::panic!`). A path under any other root
/// (`evil::panic!`) is a foreign macro with opaque expansion — rejected like
/// every other unverifiable invocation.
fn expression_macro(path: &syn::Path) -> bool {
    let Some(last) = path.segments.last() else {
        return false;
    };
    let name = last.ident.to_string();
    if !EXPRESSION_MACROS.contains(&name.as_str()) {
        return false;
    }
    if path.segments.len() == 1 {
        return true;
    }
    path.segments.first().is_some_and(|segment| {
        matches!(segment.ident.to_string().as_str(), "std" | "core" | "alloc")
    })
}

/// A `use` that could rebind a trusted macro name (`use evil::format`,
/// `use evil::x as format`) or hide its imported set entirely (`::*` — a glob
/// can import an item-splicing macro under any name, so the binding set is
/// unverifiable). Once bound, a bare `format!` no longer resolves to the
/// built-in the scan trusts — such imports are rejected rather than trusted.
fn use_tree_shadows_macro(tree: &syn::UseTree) -> bool {
    match tree {
        syn::UseTree::Path(path) => use_tree_shadows_macro(&path.tree),
        syn::UseTree::Name(name) => EXPRESSION_MACROS.contains(&name.ident.to_string().as_str()),
        syn::UseTree::Rename(rename) => {
            EXPRESSION_MACROS.contains(&rename.rename.to_string().as_str())
        }
        syn::UseTree::Glob(_) => true,
        syn::UseTree::Group(group) => group.items.iter().any(use_tree_shadows_macro),
    }
}

impl Scan {
    fn fail(&mut self, error: String) {
        if self.error.is_none() {
            self.error = Some(error);
        }
    }

    /// Runs `run` with the inner surface engaged — the entry into a body or
    /// initializer block — then restores the outer surface. Nested entry is
    /// idempotent.
    fn inside<R>(&mut self, run: impl FnOnce(&mut Self) -> R) -> R {
        let outer = std::mem::replace(&mut self.inner, true);
        let result = run(self);
        self.inner = outer;
        result
    }

    /// An `export` marker is an obligation only where the scanner models one.
    /// A marker on a position that cannot mint an `Export` — a non-callable
    /// item, an inner-surface item, a non-visible member — fails closed
    /// instead of being dropped: if `boltffi::export`/`#[no_mangle]` ever
    /// honours that position, its generated surface would carry zero
    /// obligation.
    fn unmodelable_marker(&mut self, attrs: &[syn::Attribute], position: &str) {
        match exported(attrs) {
            Ok(true) => self.fail(unmodelable_export_marker(position)),
            Err(error) => self.fail(error),
            Ok(false) => {}
        }
    }

    /// A `use` whose tree can rebind a trusted expression macro name or hide
    /// the imported set behind a glob — the macro trust boundary becomes
    /// unverifiable, so the import is rejected instead of trusted.
    fn shadowing_use(&mut self, attrs: &[syn::Attribute], tree: &syn::UseTree) {
        self.unmodelable_marker(attrs, "a use declaration");
        if use_tree_shadows_macro(tree) {
            self.fail(
                "`use` can rebind a trusted macro name (or hide one behind a \
                 glob import) — the bare-macro trust boundary becomes \
                 unverifiable; spell imports out without shadowing built-in \
                 macro names"
                    .to_owned(),
            );
        }
    }

    /// An `extern crate` cannot mint an `Export`, but `#[macro_use]` on it is
    /// not inert either: it imports every `#[macro_export]` macro of the
    /// foreign crate into textual scope, where an external `panic!`/`format!`
    /// resolves *instead of* the built-ins the bare-macro trust boundary
    /// relies on — the same shadowing channel the `use`-tree check rejects.
    /// The macro set it introduces cannot be inspected, so it fails closed.
    fn extern_crate(&mut self, extern_crate: &syn::ItemExternCrate) {
        self.unmodelable_marker(&extern_crate.attrs, "an extern crate");
        if extern_crate
            .attrs
            .iter()
            .any(|attr| meta_is_macro_use(&attr.meta))
        {
            self.fail(
                "`#[macro_use] extern crate` imports a foreign textual macro \
                 scope — external `panic!`/`format!` then shadow the built-ins \
                 the bare-macro trust boundary relies on; spell macro imports \
                 out"
                .to_owned(),
            );
        }
    }

    /// One macro invocation in statement or expression position. Attributes on
    /// the invocation are still markers (`#[export] wire!()` cannot be
    /// modeled); the invocation itself must resolve to a built-in
    /// expression/statement macro — `macro_rules!`/proc-macro expansions can
    /// splice items the obligation scan cannot see.
    fn macro_invocation(&mut self, attrs: &[syn::Attribute], mac: &syn::Macro, position: &str) {
        if self.error.is_some() {
            return;
        }
        match exported(attrs) {
            Err(error) => return self.fail(error),
            Ok(true) => {
                return self.fail(unmodelable_export_marker(&format!(
                    "a {position}-position macro invocation"
                )));
            }
            Ok(false) => {}
        }
        if !expression_macro(&mac.path) {
            self.fail(unverifiable_macro(&macro_path_identity(mac)));
        }
    }

    /// Item-position semantics: mint `Export` obligations exactly where
    /// boltffi's codegen models them, then descend — `fn`/member bodies,
    /// `const`/`static` initializers and const-generic blocks all carry the
    /// inner surface.
    fn outer_item(&mut self, item: &Item) {
        match item {
            Item::Fn(function) => {
                match exported(&function.attrs) {
                    Err(error) => return self.fail(error),
                    Ok(true) => {
                        let name = function.sig.ident.to_string();
                        self.exports.insert(Export {
                            rust: name.clone(),
                            kotlin: format!("com.lomo.nativebridge.{}", camel(&name)),
                            callback: false,
                        });
                    }
                    Ok(false) => {}
                }
                syn::visit::visit_item_fn(self, function);
            }
            Item::Impl(implementation) => self.outer_impl(implementation),
            Item::Trait(callback) => self.outer_trait(callback),
            Item::Mod(module) => {
                self.unmodelable_marker(&module.attrs, "a module");
                if let Some((_, items)) = &module.content {
                    for item in items {
                        self.visit_item(item);
                    }
                }
            }
            Item::ForeignMod(foreign) => self.foreign_mod(foreign, "an extern block"),
            Item::Macro(item_macro) => {
                self.fail(unverifiable_macro(&macro_identity(item_macro)));
            }
            Item::Use(using) => self.shadowing_use(&using.attrs, &using.tree),
            Item::ExternCrate(extern_crate) => self.extern_crate(extern_crate),
            Item::Const(_)
            | Item::Enum(_)
            | Item::Static(_)
            | Item::Struct(_)
            | Item::TraitAlias(_)
            | Item::Type(_)
            | Item::Union(_)
            | Item::Verbatim(_)
            | _ => {
                let Some(attributes) = unmodeled_item_attributes(item) else {
                    return self.fail(
                        "uninspectable item — the scan cannot prove it produces no \
                         export surface; spell `#[export]` items out"
                            .to_owned(),
                    );
                };
                self.unmodelable_marker(attributes, "a non-callable item");
                // `const`/`static` initializers and const-generic argument
                // blocks carry the inner surface — descend.
                syn::visit::visit_item(self, item);
            }
        }
    }

    /// Inner-surface item semantics: rustc still honours `#[export]` /
    /// `#[no_mangle]` here (global symbols verified on `nm`), so every marker
    /// is unmodelable — rejected. Unmarked items still descend: a nested `fn`
    /// can carry its own statement macros.
    fn inner_item(&mut self, item: &Item) {
        match item {
            Item::Fn(function) => {
                self.unmodelable_marker(&function.attrs, "a fn inside a body");
                syn::visit::visit_item_fn(self, function);
            }
            Item::Impl(implementation) => {
                self.unmodelable_marker(&implementation.attrs, "an impl block inside a body");
                for member in &implementation.items {
                    match member {
                        syn::ImplItem::Fn(member) => {
                            self.unmodelable_marker(&member.attrs, "an impl member inside a body");
                        }
                        syn::ImplItem::Const(member) => {
                            self.unmodelable_marker(&member.attrs, "an impl const inside a body");
                        }
                        syn::ImplItem::Type(member) => {
                            self.unmodelable_marker(&member.attrs, "an impl type inside a body");
                        }
                        syn::ImplItem::Macro(member) => {
                            self.fail(unverifiable_macro(&macro_path_identity(&member.mac)));
                        }
                        syn::ImplItem::Verbatim(_) | _ => self.fail(
                            "uninspectable impl member — the scan cannot prove it \
                             produces no export surface; spell `#[export]` items out"
                                .to_owned(),
                        ),
                    }
                    syn::visit::visit_impl_item(self, member);
                }
            }
            Item::Trait(callback) => {
                self.unmodelable_marker(&callback.attrs, "a trait inside a body");
                for member in &callback.items {
                    match member {
                        syn::TraitItem::Fn(member) => {
                            self.unmodelable_marker(&member.attrs, "a trait member inside a body");
                        }
                        syn::TraitItem::Const(member) => {
                            self.unmodelable_marker(&member.attrs, "a trait const inside a body");
                        }
                        syn::TraitItem::Type(member) => {
                            self.unmodelable_marker(&member.attrs, "a trait type inside a body");
                        }
                        syn::TraitItem::Macro(member) => {
                            self.fail(unverifiable_macro(&macro_path_identity(&member.mac)));
                        }
                        syn::TraitItem::Verbatim(_) | _ => self.fail(
                            "uninspectable trait member — the scan cannot prove it \
                             produces no export surface; spell `#[export]` items out"
                                .to_owned(),
                        ),
                    }
                    syn::visit::visit_trait_item(self, member);
                }
            }
            Item::Mod(module) => {
                self.unmodelable_marker(&module.attrs, "a module inside a body");
                if let Some((_, items)) = &module.content {
                    for item in items {
                        self.visit_item(item);
                    }
                }
            }
            Item::ForeignMod(foreign) => self.foreign_mod(foreign, "an extern block inside a body"),
            Item::Macro(item_macro) => {
                self.fail(unverifiable_macro(&macro_identity(item_macro)));
            }
            Item::Use(using) => self.shadowing_use(&using.attrs, &using.tree),
            Item::ExternCrate(extern_crate) => self.extern_crate(extern_crate),
            Item::Const(_)
            | Item::Enum(_)
            | Item::Static(_)
            | Item::Struct(_)
            | Item::TraitAlias(_)
            | Item::Type(_)
            | Item::Union(_)
            | Item::Verbatim(_)
            | _ => {
                let Some(attributes) = unmodeled_item_attributes(item) else {
                    return self.fail(
                        "uninspectable item — the scan cannot prove it produces no \
                         export surface; spell `#[export]` items out"
                            .to_owned(),
                    );
                };
                self.unmodelable_marker(attributes, "a non-callable item inside a body");
                syn::visit::visit_item(self, item);
            }
        }
    }

    /// An `extern "C"` block declares *imports* — symbols the foreign side
    /// provides — so its members never mint exports, at either surface. The
    /// block is still inspected: a member macro is opaque expansion (rejected
    /// like every other position), a `Verbatim` member is uninspectable, and
    /// an `export` marker on any member is an obligation the scanner cannot
    /// model.
    fn foreign_mod(&mut self, foreign: &syn::ItemForeignMod, position: &str) {
        self.unmodelable_marker(&foreign.attrs, position);
        for member in &foreign.items {
            match member {
                syn::ForeignItem::Macro(member) => {
                    self.fail(unverifiable_macro(&macro_path_identity(&member.mac)));
                }
                syn::ForeignItem::Fn(member) => {
                    self.unmodelable_marker(&member.attrs, "an extern fn");
                }
                syn::ForeignItem::Static(member) => {
                    self.unmodelable_marker(&member.attrs, "an extern static");
                }
                syn::ForeignItem::Type(member) => {
                    self.unmodelable_marker(&member.attrs, "an extern type");
                }
                syn::ForeignItem::Verbatim(_) | _ => {
                    self.fail(
                        "uninspectable extern member — the scan cannot prove it \
                         produces no export surface; spell `#[export]` items out"
                            .to_owned(),
                    );
                }
            }
        }
    }

    fn outer_impl(&mut self, implementation: &syn::ItemImpl) {
        let owner = if let syn::Type::Path(owner) = implementation.self_ty.as_ref() {
            owner
                .path
                .segments
                .last()
                .map(|segment| segment.ident.to_string())
        } else {
            None
        };
        let impl_exported = match exported(&implementation.attrs) {
            Err(error) => return self.fail(error),
            Ok(found) => found,
        };
        for member in &implementation.items {
            match member {
                syn::ImplItem::Fn(function) => {
                    let marked = match exported(&function.attrs) {
                        Err(error) => return self.fail(error),
                        Ok(found) => found,
                    };
                    if !matches!(function.vis, Visibility::Public(_)) {
                        if marked {
                            return self
                                .fail(unmodelable_export_marker("a non-public impl member"));
                        }
                    } else if impl_exported || marked {
                        let Some(owner) = &owner else {
                            return self.fail(unmodelable_export_marker(
                                "an impl member on an unresolvable self type",
                            ));
                        };
                        let name = function.sig.ident.to_string();
                        let static_part = if function.sig.receiver().is_none() {
                            ".Companion"
                        } else {
                            ""
                        };
                        self.exports.insert(Export {
                            rust: format!("{owner}::{name}"),
                            kotlin: format!(
                                "com.lomo.nativebridge.{owner}{static_part}.{}",
                                camel(&name)
                            ),
                            callback: false,
                        });
                    }
                }
                syn::ImplItem::Macro(member) => {
                    self.fail(unverifiable_macro(&macro_path_identity(&member.mac)));
                }
                syn::ImplItem::Const(member) => {
                    self.unmodelable_marker(&member.attrs, "an impl const");
                }
                syn::ImplItem::Type(member) => {
                    self.unmodelable_marker(&member.attrs, "an impl type");
                }
                syn::ImplItem::Verbatim(_) | _ => {
                    self.fail(
                        "uninspectable impl member — the scan cannot prove it produces no \
                         export surface; spell `#[export]` items out"
                            .to_owned(),
                    );
                }
            }
            syn::visit::visit_impl_item(self, member);
        }
    }

    fn outer_trait(&mut self, callback: &syn::ItemTrait) {
        let trait_exported = match exported(&callback.attrs) {
            Err(error) => return self.fail(error),
            Ok(found) => found,
        };
        for member in &callback.items {
            match member {
                syn::TraitItem::Fn(function) => {
                    if trait_exported {
                        let owner = callback.ident.to_string();
                        let name = function.sig.ident.to_string();
                        self.exports.insert(Export {
                            rust: format!("{owner}::{name}"),
                            kotlin: format!("com.lomo.nativebridge.{owner}.{}", camel(&name)),
                            callback: true,
                        });
                    } else {
                        self.unmodelable_marker(
                            &function.attrs,
                            "a trait member without an exported trait",
                        );
                    }
                }
                syn::TraitItem::Macro(member) => {
                    self.fail(unverifiable_macro(&macro_path_identity(&member.mac)));
                }
                syn::TraitItem::Const(member) => {
                    self.unmodelable_marker(&member.attrs, "a trait const");
                }
                syn::TraitItem::Type(member) => {
                    self.unmodelable_marker(&member.attrs, "a trait type");
                }
                syn::TraitItem::Verbatim(_) | _ => {
                    self.fail(
                        "uninspectable trait member — the scan cannot prove it produces no \
                         export surface; spell `#[export]` items out"
                            .to_owned(),
                    );
                }
            }
            syn::visit::visit_trait_item(self, member);
        }
    }
}

impl<'ast> Visit<'ast> for Scan {
    fn visit_item(&mut self, item: &'ast Item) {
        if self.error.is_some() {
            return;
        }
        if self.inner {
            self.inner_item(item);
        } else {
            self.outer_item(item);
        }
    }

    /// A block — `fn`/member bodies, `const`/`static` initializer blocks,
    /// anonymous blocks — is the inner item surface.
    fn visit_block(&mut self, block: &'ast syn::Block) {
        self.inside(|scan| syn::visit::visit_block(scan, block));
    }

    /// A `{ ... }` expression (including const-generic argument position) is
    /// the inner item surface.
    fn visit_expr_block(&mut self, block: &'ast syn::ExprBlock) {
        self.inside(|scan| syn::visit::visit_expr_block(scan, block));
    }

    /// A `const { ... }` expression is the inner item surface.
    fn visit_expr_const(&mut self, konst: &'ast syn::ExprConst) {
        self.inside(|scan| syn::visit::visit_expr_const(scan, konst));
    }

    fn visit_local(&mut self, local: &'ast syn::Local) {
        if self.error.is_some() {
            return;
        }
        self.unmodelable_marker(&local.attrs, "a local binding");
        syn::visit::visit_local(self, local);
    }

    fn visit_stmt_macro(&mut self, statement: &'ast syn::StmtMacro) {
        self.macro_invocation(&statement.attrs, &statement.mac, "statement");
    }

    fn visit_expr_macro(&mut self, expression: &'ast syn::ExprMacro) {
        self.macro_invocation(&expression.attrs, &expression.mac, "expression");
    }

    /// Inside the inner surface an export marker can never mint — rustc and
    /// boltffi honor item position only — so every marker seen there fails
    /// closed. This also covers positions no item handler owns:
    /// expression/statement attributes (`#[attr] expr`, accepted by
    /// `stmt_expr_attributes`) and inner-attribute forms. Outer-surface
    /// attributes are accounted by the item handlers themselves (or by the
    /// `file.attrs` check in [`exports`]), so they are not re-judged here.
    fn visit_attribute(&mut self, attribute: &'ast syn::Attribute) {
        if self.error.is_some() {
            return;
        }
        if self.inner {
            match meta_is_export(&attribute.meta) {
                Err(error) => self.fail(error),
                Ok(true) => self.fail(unmodelable_export_marker("a non-item position")),
                Ok(false) => {}
            }
        }
        syn::visit::visit_attribute(self, attribute);
    }
}

pub fn exports(source: &str) -> Result<BTreeSet<Export>, String> {
    let file = syn::parse_file(source).map_err(|error| error.to_string())?;
    let mut scan = Scan {
        exports: BTreeSet::new(),
        inner: false,
        error: None,
    };
    // `#![...]` inner attributes apply to the enclosing item — the file
    // itself — and mint nothing the scanner can model (an inline module's
    // `#![...]` attrs land in `ItemMod.attrs` and are checked the same way by
    // `outer_item`/`inner_item`).
    scan.unmodelable_marker(&file.attrs, "a file-level `#![...]` attribute");
    scan.visit_file(&file);
    if let Some(error) = scan.error {
        return Err(error);
    }
    Ok(scan.exports)
}

/// Whether an attribute set carries a symbol-emitting marker — `boltffi`'s
/// `export` or rustc's own `no_mangle`/`export_name`, which mint the same
/// unmanaged global symbol at any item position. `#[cfg_attr(<cond>,
/// <attr>)]` re-emits `<attr>` whenever `<cond>` holds, so a cfg-gated export is still a
/// real export obligation: the gate does not model cfg states, and treating it as an
/// export fails closed (an absent generated symbol is flagged rather than ignored).
///
/// # Errors
/// A malformed `cfg_attr` argument list fails closed: the scanner cannot prove the
/// attribute does not re-emit an export, so the source is rejected instead of silently
/// skipping the item.
fn exported(attributes: &[syn::Attribute]) -> Result<bool, String> {
    attributes.iter().try_fold(false, |found, attribute| {
        if found {
            return Ok(true);
        }
        meta_is_export(&attribute.meta)
    })
}

/// One meta-level attribute entry is an export marker when its path ends in
/// `export`, `no_mangle` or `export_name` — all three mint an unmanaged global
/// symbol (boltffi's through codegen, rustc's directly), so the gate holds
/// them to the same modeled-or-rejected rule. `cfg_attr(<cond>, <attrs...>)`
/// wraps a condition as its first comma item — parsed as
/// `Punctuated<Meta, Comma>` so list-shaped conditions (`all(...)`/`any(...)`/`not(...)`)
/// cannot abort the scan before the re-emitted attributes are inspected — and each
/// re-emitted attribute is itself checked recursively. `unsafe(<attrs...>)` is
/// the *only* legal edition-2024 spelling of `no_mangle`/`export_name` (rustc
/// rejects the bare forms and `nm` shows the wrapped ones still emit global
/// symbols), so its argument list is opened the same way.
fn meta_is_export(meta: &Meta) -> Result<bool, String> {
    if meta.path().segments.last().is_some_and(|segment| {
        matches!(
            segment.ident.to_string().as_str(),
            "export" | "no_mangle" | "export_name"
        )
    }) {
        return Ok(true);
    }
    let Meta::List(list) = meta else {
        return Ok(false);
    };
    if list.path.is_ident("unsafe") {
        let args = list
            .parse_args_with(Punctuated::<Meta, syn::Token![,]>::parse_terminated)
            .map_err(|error| format!("unparseable unsafe attribute arguments: {error}"))?;
        return args
            .iter()
            .try_fold(false, |found, meta| Ok(found || meta_is_export(meta)?));
    }
    if !list.path.is_ident("cfg_attr") {
        return Ok(false);
    }
    let mut args = list
        .parse_args_with(Punctuated::<Meta, syn::Token![,]>::parse_terminated)
        .map_err(|error| format!("unparseable cfg_attr arguments: {error}"))?
        .into_iter();
    args.next(); // the cfg condition: any meta shape is a legal condition
    args.try_fold(false, |found, meta| Ok(found || meta_is_export(&meta)?))
}

/// One meta-level attribute entry wraps `macro_use` — the `#[macro_use]`
/// marker that imports a foreign textual macro scope. Recursed through the
/// same `cfg_attr`/`unsafe` list wrappers as [`meta_is_export`]; an
/// unparseable argument list counts as carrying the marker (fail closed: the
/// scan cannot prove it does not).
fn meta_is_macro_use(meta: &Meta) -> bool {
    if meta.path().is_ident("macro_use") {
        return true;
    }
    let Meta::List(list) = meta else {
        return false;
    };
    if !(list.path.is_ident("cfg_attr") || list.path.is_ident("unsafe")) {
        return false;
    }
    let Ok(args) = list.parse_args_with(Punctuated::<Meta, syn::Token![,]>::parse_terminated)
    else {
        return true;
    };
    let mut args = args.into_iter();
    if list.path.is_ident("cfg_attr") {
        args.next(); // the cfg condition
    }
    args.any(|meta| meta_is_macro_use(&meta))
}

/// A readable identity for an item-position macro: `macro_rules!` definitions carry
/// `ident`, bare invocations carry the path (e.g. `some_crate::wire!`).
fn macro_identity(item_macro: &syn::ItemMacro) -> String {
    item_macro
        .ident
        .as_ref()
        .map_or_else(|| macro_path_identity(&item_macro.mac), ToString::to_string)
}

/// The trailing path segment of a member-position macro invocation (`wire!{}`
/// inside an impl/trait/extern block carries no `ident`).
fn macro_path_identity(mac: &syn::Macro) -> String {
    mac.path.segments.last().map_or_else(
        || "<unresolved>".to_owned(),
        |segment| segment.ident.to_string(),
    )
}

/// Macro expansion is opaque to this scan: a `#[export]` item produced by
/// `wire!{}` carries no obligation the gate can verify. Every macro — item or
/// member position, definition or invocation — is rejected with the same
/// obligation instead of being skipped.
fn unverifiable_macro(identity: &str) -> String {
    format!(
        "unverifiable macro `{identity}!` — the foreign export surface cannot \
         hide behind macro expansion; spell `#[export]` items out"
    )
}

/// An `export` marker is an obligation only where the scanner models one. A
/// marker on a shape that produces no `Export` — a non-callable item, an
/// inner-surface item, a non-visible member, an extern-block member — fails
/// closed instead of being dropped: if `boltffi::export` ever honours that
/// position, its generated surface would carry zero obligation.
fn unmodelable_export_marker(position: &str) -> String {
    format!(
        "`export` marker on {position} cannot be modeled — the scanner cannot \
         prove what it generates; spell exports on callable items only"
    )
}

/// Attributes carried by an item kind the scanner does not model. `Verbatim`
/// and any future item variant are opaque: they report no attributes at all —
/// and the modeled kinds (`Fn`/`Impl`/`Trait`/`Mod`/`ForeignMod`/`Macro`) never
/// reach the call site that consults this table.
fn unmodeled_item_attributes(item: &Item) -> Option<&[syn::Attribute]> {
    match item {
        Item::Const(item) => Some(&item.attrs),
        Item::Enum(item) => Some(&item.attrs),
        Item::ExternCrate(item) => Some(&item.attrs),
        Item::Static(item) => Some(&item.attrs),
        Item::Struct(item) => Some(&item.attrs),
        Item::TraitAlias(item) => Some(&item.attrs),
        Item::Type(item) => Some(&item.attrs),
        Item::Union(item) => Some(&item.attrs),
        Item::Use(item) => Some(&item.attrs),
        Item::Fn(_)
        | Item::Impl(_)
        | Item::Macro(_)
        | Item::Mod(_)
        | Item::ForeignMod(_)
        | Item::Trait(_)
        | Item::Verbatim(_)
        | _ => None,
    }
}

fn camel(name: &str) -> String {
    let mut upper = false;
    let mut result = String::new();
    for character in name.trim_start_matches("r#").chars() {
        if character == '_' {
            upper = true;
        } else if upper {
            result.extend(character.to_uppercase());
            upper = false;
        } else {
            result.push(character);
        }
    }
    result
}

/// The resolved fact graph emitted per production source file.
///
/// - `declarations`: callable identities declared in production sources.
/// - `classes`: fully-qualified class/object identities declared in production sources.
/// - `roots`: holder classes the platform itself instantiates or dispatches to
///   (activities, services, receivers, workers, view models, widgets, views, fragments,
///   providers). A member identity is never a valid root — an override root would let an
///   uninstantiated class satisfy a consumer obligation. A declared platform subtype is
///   not an entry point either until something can instantiate it: manifest
///   registration, a construction call edge, or a factory reference handed to a
///   consumer (`viewModelOf(::Vm)`/`workerOf(::W)` DI registration).
/// - `edges`: `caller → callee` resolved calls (constructors edge to the constructed
///   class identity), `holder → member` containment, `base → override` dispatch, and
///   `enclosing → local` edges for local/anonymous declarations.
/// - `references`: `caller → target` for callable references handed to a call as a
///   value argument — the only position where the reference escapes into a callee that
///   may invoke it (DI factories). A reference bound to a property or dropped is inert
///   and emits nothing. References never satisfy a consumer obligation; they only count
///   as registration evidence when validating entry points.
/// - `manifest`: classes declared as components in a module `AndroidManifest.xml` —
///   the platform instantiates them without any in-graph call site.
#[derive(Default)]
pub struct ResolvedGraph {
    pub declarations: BTreeSet<String>,
    pub classes: BTreeSet<String>,
    pub roots: BTreeSet<String>,
    pub edges: BTreeSet<(String, String)>,
    pub references: BTreeSet<(String, String)>,
    pub manifest: BTreeSet<String>,
}

/// Production `LomoApplication.onCreate` reflectively installs data Koin modules.
pub const APP_RUNTIME_DATA_KOIN_INSTALLER: &str = "com.lomo.app.LomoApplication.onCreate";

/// Resolved callable id of `val dataModules` in `com.lomo.data.di.DataModules`.
pub const APP_RUNTIME_DATA_KOIN_INSTALLED: &str = "com.lomo.data.di.dataModules";

/// The source the modeled runtime edge stands in for: `LomoApplication.kt` loads
/// `com.lomo.data.di.DataModulesKt` reflectively and calls `getDataModules`.
const APP_RUNTIME_INSTALLER_SOURCE: &str = "apps/android/app/src/LomoApplication.kt";

/// `name` used as a call — the next non-whitespace character after the
/// identifier is `(`. A mention inside a longer identifier does not count.
/// Runs on [`kotlin_code_view`]'s `code` view: literal/comment contents are masked,
/// so a retained doc string cannot mint a call shape.
fn contains_call_shape(stripped: &str, name: &str) -> bool {
    let mut rest = stripped;
    while let Some(position) = rest.find(name) {
        let (_, after) = rest.split_at(position + name.len());
        let extends = after
            .chars()
            .next()
            .is_some_and(|c| c.is_alphanumeric() || c == '_');
        if !extends && after.trim_start().starts_with('(') {
            return true;
        }
        rest = after;
    }
    false
}

/// One module-level contract edge for the app→data reflection install (not a symbol
/// allowlist). The modeled edge is only valid while the source fact it stands in for
/// still exists: `LomoApplication.kt` must still reflectively load `DataModulesKt` and
/// read `getDataModules` — otherwise the phantom edge would keep the whole data-module
/// consumer graph reachable after the install was deleted (fail-open).
///
/// The check runs on the shared [`kotlin_code_view`] dual view: `forName(`/`getMethod(`
/// must appear as call shapes in the `code` view — literal and comment contents
/// are masked there, so a `val note = "Class.forName(…)"` leftover cannot keep
/// the edge alive — while the `DataModulesKt`/`getDataModules` names are read on
/// the `evidence` view because they legitimately arrive as string arguments.
/// A fabricated string payload without executable calls still fails.
///
/// # Errors
/// Returns an error when the installer source is missing or no longer performs the
/// reflective `DataModulesKt`/`getDataModules` load the edge models.
pub fn install_app_data_koin_runtime_edge(
    root: &Path,
    graph: &mut ResolvedGraph,
) -> Result<(), String> {
    let source_path = root.join(APP_RUNTIME_INSTALLER_SOURCE);
    let source = fs::read_to_string(&source_path)
        .map_err(|error| format!("{}: {error}", source_path.display()))?;
    let text = kotlin_code_view(&source);
    for call in ["forName", "getMethod"] {
        if !contains_call_shape(&text.code, call) {
            return Err(format!(
                "{APP_RUNTIME_INSTALLER_SOURCE} no longer calls `{call}(...)` — the \
                 modeled app→data Koin install edge lost its source fact; remove it or \
                 re-derive the reachability it stood in for"
            ));
        }
    }
    for needle in ["DataModulesKt", "getDataModules"] {
        if !text.evidence.contains(needle) {
            return Err(format!(
                "{APP_RUNTIME_INSTALLER_SOURCE} no longer contains `{needle}` — the \
                 modeled app→data Koin install edge lost its source fact; remove it or \
                 re-derive the reachability it stood in for"
            ));
        }
    }
    graph.edges.insert((
        APP_RUNTIME_DATA_KOIN_INSTALLER.to_owned(),
        APP_RUNTIME_DATA_KOIN_INSTALLED.to_owned(),
    ));
    Ok(())
}

pub fn contract_violations(
    exports: &BTreeSet<Export>,
    generated: &BTreeSet<String>,
    graph: &ResolvedGraph,
) -> Vec<Violation> {
    let mut violations = Vec::new();
    // Instantiation evidence must come from a *live* context. Manifest registration
    // is unconditional platform evidence, so manifest-declared holder classes seed
    // reachability directly. A construction edge or a factory reference mints an
    // entry point only when its *source* is reachable — `X()` or `register(::X)`
    // inside dead code constructs nothing at runtime. The worklist therefore
    // interleaves evidence validation with propagation: every edge target becomes
    // reachable, and a `references` target that is a declared root class becomes an
    // entry point when the registration site itself is live.
    let mut reachable: BTreeSet<String> = BTreeSet::new();
    let mut queue: Vec<String> = Vec::new();
    for root in &graph.roots {
        if graph.classes.contains(root)
            && graph.manifest.contains(root)
            && reachable.insert(root.clone())
        {
            queue.push(root.clone());
        }
    }
    let mut calls: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for (from, to) in &graph.edges {
        calls.entry(from).or_default().push(to);
    }
    let mut registrations: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for (from, to) in &graph.references {
        registrations.entry(from).or_default().push(to);
    }
    while let Some(from) = queue.pop() {
        if let Some(targets) = calls.get(from.as_str()) {
            for target in targets {
                if reachable.insert((*target).to_owned()) {
                    queue.push((*target).to_owned());
                }
            }
        }
        if let Some(targets) = registrations.get(from.as_str()) {
            for target in targets {
                if graph.roots.contains(*target)
                    && graph.classes.contains(*target)
                    && reachable.insert((*target).to_owned())
                {
                    queue.push((*target).to_owned());
                }
            }
        }
    }
    for root in &graph.roots {
        if !graph.classes.contains(root) {
            violations.push(Violation::new(
                "ffi-graph-entry",
                root,
                format!("{root} is not a declared production holder class"),
            ));
        } else if !reachable.contains(root) {
            violations.push(Violation::new(
                "ffi-graph-entry",
                root,
                format!(
                    "{root} is a declared platform host subtype but nothing \
                     manifests it and no live path constructs or registers it — \
                     declaration is not instantiation"
                ),
            ));
        }
    }
    for export in exports {
        if !generated.contains(&export.kotlin) {
            violations.push(Violation::new(
                "ffi-generated-declaration",
                &export.rust,
                format!("missing generated symbol {}", export.kotlin),
            ));
        }
        let consumed = if export.callback {
            graph.edges.iter().any(|(base, implementation)| {
                base == &export.kotlin && reachable.contains(implementation)
            })
        } else {
            reachable.contains(&export.kotlin)
        };
        if !consumed {
            violations.push(Violation::new(
                "ffi-production-consumer",
                &export.rust,
                format!(
                    "{} has no reachable consuming adapter or callback implementation",
                    export.kotlin
                ),
            ));
        }
    }
    violations
}

/// One meta entry wraps a `path` directive — a top-level `path = "..."` or a
/// `path` re-emitted inside `cfg_attr(<cond>, <attrs...>)` (itself possibly
/// `unsafe`- or `cfg_attr`-nested). Recursion mirrors [`meta_is_export`]; an
/// unparseable argument list counts as carrying a `path` (fail closed: the
/// scan cannot prove it does not).
fn meta_wraps_path(meta: &Meta) -> bool {
    if meta.path().is_ident("path") {
        return true;
    }
    let Meta::List(list) = meta else {
        return false;
    };
    if !(list.path.is_ident("cfg_attr") || list.path.is_ident("unsafe")) {
        return false;
    }
    let Ok(args) = list.parse_args_with(Punctuated::<Meta, syn::Token![,]>::parse_terminated)
    else {
        return true;
    };
    let mut args = args.into_iter();
    if list.path.is_ident("cfg_attr") {
        args.next(); // the cfg condition
    }
    args.any(|meta| meta_wraps_path(&meta))
}

/// The `#[path = "..."]` value on a module declaration, as a string. A
/// `#[path]` attribute that is not a string name-value is unresolvable for the
/// scanner — rejected rather than guessed. `#[cfg_attr(<cond>, path = "...")]`
/// makes the compiled target cfg-dependent, which this gate does not model:
/// without evaluating `<cond>` the scanner cannot prove which file rustc
/// compiles, so the module fails closed instead of scanning a `name.rs` decoy.
fn path_attribute(attrs: &[syn::Attribute]) -> Result<Option<String>, String> {
    for attr in attrs {
        if !attr.path().is_ident("path") {
            if meta_wraps_path(&attr.meta) {
                return Err(
                    "`#[cfg_attr]`-conditional `#[path]` makes the module target \
                     unverifiable — the gate does not model cfg states"
                        .to_owned(),
                );
            }
            continue;
        }
        let Meta::NameValue(name_value) = &attr.meta else {
            return Err("`#[path]` attribute is not a string name-value".to_owned());
        };
        let syn::Expr::Lit(expr_lit) = &name_value.value else {
            return Err("`#[path]` value is not a literal".to_owned());
        };
        let syn::Lit::Str(literal) = &expr_lit.lit else {
            return Err("`#[path]` value is not a string literal".to_owned());
        };
        return Ok(Some(literal.value()));
    }
    Ok(None)
}

/// `base.join(rel)` with `.`/`..` components folded textually. rustc resolves
/// `#[path]` through the filesystem — which requires every traversed
/// directory to exist — but the scan only needs the *target* rustc would
/// read: folding `a/m/../../x` to `a/../x` points the inventory at the same
/// file without demanding intermediate dirs exist (a symlink traversal is
/// still caught by the canonical boundary check downstream).
fn join_normalized(base: &Path, rel: &str) -> PathBuf {
    let mut out = base.to_path_buf();
    for component in Path::new(rel).components() {
        match component {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                out.pop();
            }
            std::path::Component::Prefix(_)
            | std::path::Component::RootDir
            | std::path::Component::Normal(_) => out.push(component.as_os_str()),
        }
    }
    out
}

/// File targets a parsed source pulls into the module graph: `mod name;`
/// declarations (resolved `dir/name.rs` then `dir/name/mod.rs`, mirroring
/// rustc) and `#[path]` targets (resolved verbatim). Inline modules push one
/// directory segment — `#[path]` on an inline mod names the directory for its
/// children, exactly like rustc — and declarations inside `fn` bodies count
/// too: an inner `#[path]` mod joins the compile surface identically.
///
/// rustc gives a module declared in a *non-*`mod.rs` file its own `stem/`
/// module directory: `mod child;` inside `name.rs` resolves `dir/name/child.rs`,
/// and inside inline `mod m` of `name.rs` it resolves `dir/name/m/…`. The
/// `#[path]` attribute is asymmetric: at the file's top level it resolves in
/// the *file's* directory, while inside inline modules it resolves in the
/// enclosing module directory — `dir/name/m/<rel>` for `name.rs`. Files that
/// own no `stem/` directory — `mod.rs`, crate roots, `#[path]`-loaded files —
/// keep the file's directory as their module directory.
///
/// A module declared inside a *block* (`fn`/member bodies, `const`/`static`
/// initializers, `{ }` expressions — every syn `Block`) drops the file's own
/// `stem/` contribution: `fn f() { mod m { #[path="x.rs"] mod i; } }` inside
/// `src/evil.rs` compiles `src/m/x.rs`, not `src/evil/m/x.rs` (verified
/// against rustc 1.98). An enclosing item-level inline chain still
/// contributes — `mod n { fn f() { mod a { #[path="x.rs"] mod i; } } }`
/// compiles `src/evil/n/a/x.rs` — so only the empty-stack fallback changes:
/// inside a block it is `file_dir` (the file's directory, before the `stem/`
/// component) rather than `mod_dir`. Inside `mod m`'s item list the file is
/// again at item level for that module, so the flag resets while the
/// inline-module's items are visited.
struct ModRefs {
    /// The file's own directory — a `#[path]` on a top-level `mod` resolves
    /// here even when the file owns a `stem/` module directory, and inside a
    /// block the `stem/` component drops entirely.
    file_dir: PathBuf,
    /// The file's module directory — `mod x;` and inline-module children at
    /// *item* level resolve under it.
    mod_dir: PathBuf,
    /// `true` while descending through a block — where rustc's module-dir
    /// model roots an inline `mod`'s directory at `file_dir`, not `mod_dir`.
    block: bool,
    /// The resolved child directory of each enclosing inline `mod` — `dir/m`
    /// for `mod m { ... }`, or the directory its `#[path]` names.
    stack: Vec<PathBuf>,
    /// `(file, module dir)` pairs this source pulls into the compile surface.
    targets: Vec<(PathBuf, PathBuf)>,
    error: Option<String>,
}

impl ModRefs {
    fn fail(&mut self, error: String) {
        if self.error.is_none() {
            self.error = Some(error);
        }
    }
}

impl<'ast> Visit<'ast> for ModRefs {
    /// syn routes every block-shaped surface — `fn`/member bodies,
    /// `const`/`static` initializers, `{ }`/`unsafe`/`async`/`const`
    /// expressions, closures, loop bodies — through `visit_block`, so this
    /// one override observes every block boundary rustc draws for module
    /// resolution.
    fn visit_block(&mut self, block: &'ast syn::Block) {
        let outer = std::mem::replace(&mut self.block, true);
        syn::visit::visit_block(self, block);
        self.block = outer;
    }

    fn visit_item_mod(&mut self, module: &'ast syn::ItemMod) {
        if self.error.is_some() {
            return;
        }
        // Inside a block the empty-stack base is the file's directory — the
        // file's `stem/` module dir only exists for item-level declarations.
        let dir = self.stack.last().cloned().unwrap_or_else(|| {
            if self.block {
                self.file_dir.clone()
            } else {
                self.mod_dir.clone()
            }
        });
        let path_attr = match path_attribute(&module.attrs) {
            Err(error) => return self.fail(error),
            Ok(value) => value,
        };
        // `#[path]` at the file's top level resolves in the file's directory;
        // inside inline modules it resolves in the enclosing module dir.
        let path_dir = if self.stack.is_empty() {
            &self.file_dir
        } else {
            &dir
        };
        if let Some((_, items)) = &module.content {
            // An inline module's children resolve under `#[path]` (a directory)
            // or under the module name. The items are module items, not block
            // contents — the block flag does not carry into them.
            let child_dir = path_attr.as_ref().map_or_else(
                || dir.join(module.ident.to_string()),
                |rel| join_normalized(path_dir, rel),
            );
            self.stack.push(child_dir);
            let outer = std::mem::replace(&mut self.block, false);
            for item in items {
                self.visit_item(item);
            }
            self.block = outer;
            self.stack.pop();
            return;
        }
        if let Some(rel) = path_attr {
            let target = join_normalized(path_dir, &rel);
            if target.is_file() {
                // A `#[path]`-loaded file's own children resolve in the file's
                // directory — rustc treats it like `mod.rs` there.
                let child_mod_dir = target
                    .parent()
                    .map_or_else(|| target.clone(), Path::to_path_buf);
                self.targets.push((target, child_mod_dir));
            } else {
                // rustc rejects a dangling `#[path]` — the invalid upstream
                // state is surfaced, not silently dropped.
                self.fail(format!(
                    "`#[path]` module target does not exist: {}",
                    target.display()
                ));
            }
        } else {
            let candidates = [
                dir.join(format!("{}.rs", module.ident)),
                dir.join(module.ident.to_string()).join("mod.rs"),
            ];
            let existing: Vec<&PathBuf> = candidates.iter().filter(|path| path.is_file()).collect();
            match existing.as_slice() {
                // The declared module's own directory is `dir/name` either
                // way — `name.rs`'s `stem/` dir and `name/mod.rs`'s parent.
                [one] => self
                    .targets
                    .push(((*one).clone(), dir.join(module.ident.to_string()))),
                [] => self.fail(format!(
                    "`mod {}` resolves to no file under {}",
                    module.ident,
                    dir.display()
                )),
                _ => self.fail(format!(
                    "`mod {}` resolves ambiguously under {}",
                    module.ident,
                    dir.display()
                )),
            }
        }
    }
}

/// The module directory a `src/` file owns when the module graph never named
/// it — the rustc shape it would have if it were compiled as `mod <stem>`:
/// `mod.rs` keeps its parent directory (it already *is* the directory
/// module), every other file gets the `stem/` directory rustc reserves for
/// non-`mod.rs` module files.
fn orphan_module_dir(file: &Path) -> PathBuf {
    let dir = file
        .parent()
        .map_or_else(|| file.to_path_buf(), Path::to_path_buf);
    if file.file_name().is_some_and(|name| name == "mod.rs") {
        return dir;
    }
    file.file_stem()
        .map_or_else(|| dir.clone(), |stem| dir.join(stem))
}

/// Filesystem enumeration of the native facade compile surface: the `src/`
/// directory tree plus every file rustc pulls in through `mod name;` and
/// `#[path]` declarations — the module graph, not the directory tree, decides
/// compilation. `#[path]` can join a file from outside `src/` exactly like the
/// once-gitignored `gen/` module could, so the inventory resolves it relative
/// to the declaring file's directory, canonicalizes it, and holds it to the
/// same repository boundary. Symlink loops fail closed through
/// canonical-directory visitation instead of recursing forever.
///
/// Each worklist entry carries the file's *module directory* — where its
/// `mod x;`/inline-`mod` children resolve — because rustc gives a
/// non-`mod.rs` file its own `stem/` directory while `mod.rs`, crate roots
/// and `#[path]`-loaded files use the file's directory. The crate roots are
/// pushed last so they pop first: the whole rustc module graph drains
/// depth-first before the directory sweep claims anything, so a file the
/// graph reaches is parsed under its true module dir and the sweep only
/// supplies the positional one for files rustc never named.
///
/// What rustc *parses* is likewise graph-shaped: a `#[path]` target compiles
/// as Rust whatever its extension — extension filters belong to the
/// directory-tree side only (a stray non-`.rs` file under `src/` is inventory
/// but never a compile unit). Returns the files that must be scanned for
/// exports: every module-graph target plus every `.rs` file in the tree.
fn native_source_tree(root: &Path) -> Result<BTreeSet<PathBuf>, String> {
    let canonical_root = root
        .canonicalize()
        .map_err(|error| format!("{}: {error}", root.display()))?;
    let mut files = Vec::new();
    let mut active = BTreeSet::new();
    let native_src = root.join("crates/lomo-native/src");
    collect_source_tree(&native_src, &canonical_root, &mut files, &mut active)?;
    // Sort so the sweep order — and thus which interpretation claims an
    // ambiguously-reached file first — never depends on `read_dir` order.
    files.sort();
    let mut scanned = BTreeSet::new();
    // `canonical → module dirs` for every file already parsed. rustc compiles
    // one source file once per module identity claiming it: `mod a;` reads
    // `dir/a.rs` with children under `dir/a`, while `#[path="a.rs"] mod b;`
    // compiles the same file as a second module whose children resolve in the
    // file's own directory — legal aliasing verified against rustc — so each
    // distinct (file, dir) interpretation parses once and contributes its own
    // child edges. The export scan itself still dedupes the file (`scanned`):
    // a file's `#[export]` obligations are identical under every module
    // identity. A repeated (file, dir) pair is the same interpretation —
    // skipped — and a directory-sweep entry always loses to any graph claim,
    // since the sweep's positional dir was only ever a fallback.
    let mut parsed: BTreeMap<PathBuf, BTreeSet<PathBuf>> = BTreeMap::new();
    // Tree entries wait on `tree`; graph targets (crate roots, `mod`,
    // `#[path]` discoveries) stack on `graph` and always pop first — the
    // entire discoverable module graph drains before the directory sweep, so
    // a file rustc reaches is parsed under its true module dir and the sweep
    // only supplies the positional one for files rustc never named.
    let mut tree: Vec<(PathBuf, PathBuf)> = files
        .into_iter()
        .map(|file| {
            let mod_dir = orphan_module_dir(&file);
            (file, mod_dir)
        })
        .collect();
    let mut graph: Vec<(PathBuf, PathBuf)> = Vec::new();
    for seed_name in ["lib.rs", "main.rs"] {
        let seed = native_src.join(seed_name);
        if seed.is_file() {
            let seed_dir = seed
                .parent()
                .map_or_else(|| seed.clone(), Path::to_path_buf);
            graph.push((seed, seed_dir));
        }
    }
    while let Some((file, via_graph, mod_dir)) = graph
        .pop()
        .map(|(file, mod_dir)| (file, true, mod_dir))
        .or_else(|| tree.pop().map(|(file, mod_dir)| (file, false, mod_dir)))
    {
        let canonical = file
            .canonicalize()
            .map_err(|error| format!("{}: {error}", file.display()))?;
        if !canonical.starts_with(&canonical_root) {
            return Err(format!(
                "source escapes repository ownership: {}",
                file.display()
            ));
        }
        // A file the module graph names is a compile unit however it is
        // spelled; a directory-tree file is one only under the `.rs`
        // convention.
        let compiles = via_graph || file.extension().is_some_and(|extension| extension == "rs");
        if !compiles {
            continue;
        }
        {
            let dirs = parsed.entry(canonical.clone()).or_default();
            // A tree entry loses to any claimed graph dir — the sweep
            // interpretation was only ever a fallback.
            if !via_graph && !dirs.is_empty() {
                continue;
            }
            // Same interpretation already covered — `mod a;` re-claimed under
            // `dir/a`, or a `#[path]` alias re-stating a known dir.
            if !dirs.insert(mod_dir.clone()) {
                continue;
            }
        }
        scanned.insert(canonical);
        let source =
            fs::read_to_string(&file).map_err(|error| format!("{}: {error}", file.display()))?;
        let parsed_file = syn::parse_file(&source).map_err(|error| {
            format!(
                "{}: unparseable for module resolution: {error}",
                file.display()
            )
        })?;
        let mut refs = ModRefs {
            file_dir: file
                .parent()
                .map_or_else(|| file.clone(), Path::to_path_buf),
            mod_dir,
            block: false,
            stack: Vec::new(),
            targets: Vec::new(),
            error: None,
        };
        refs.visit_file(&parsed_file);
        if let Some(error) = refs.error {
            return Err(format!("{}: {error}", file.display()));
        }
        graph.extend(refs.targets);
    }
    Ok(scanned)
}

fn collect_source_tree(
    dir: &Path,
    canonical_root: &Path,
    files: &mut Vec<PathBuf>,
    active: &mut BTreeSet<PathBuf>,
) -> Result<(), String> {
    let canonical_dir = dir
        .canonicalize()
        .map_err(|error| format!("{}: {error}", dir.display()))?;
    if !canonical_dir.starts_with(canonical_root) {
        return Err(format!(
            "source escapes repository ownership: {}",
            dir.display()
        ));
    }
    if !active.insert(canonical_dir.clone()) {
        return Err(format!("symlink cycle in source tree: {}", dir.display()));
    }
    for entry in fs::read_dir(dir).map_err(|error| format!("{}: {error}", dir.display()))? {
        let path = entry.map_err(|error| error.to_string())?.path();
        if path.is_dir() {
            collect_source_tree(&path, canonical_root, files, active)?;
        } else if path.is_file() {
            files.push(path);
        }
    }
    active.remove(&canonical_dir);
    Ok(())
}

pub fn workspace_exports(root: &Path) -> Result<BTreeSet<Export>, String> {
    let mut surface = BTreeSet::new();
    for path in native_source_tree(root)? {
        for export in exports(&fs::read_to_string(&path).map_err(|error| error.to_string())?)? {
            if !surface.insert(export.clone()) {
                return Err(format!("duplicate foreign export {}", export.rust));
            }
        }
    }
    if surface.is_empty() {
        return Err("native facade contains no explicit exports".to_owned());
    }
    Ok(surface)
}

/// Reads a `[from, to]` pair list from a fact document into `target`.
fn read_pairs(
    facts: &serde_json::Value,
    key: &str,
    target: &mut BTreeSet<(String, String)>,
) -> Result<(), String> {
    for edge in facts
        .get(key)
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| format!("{key} edges missing"))?
    {
        let values = edge.as_array().ok_or("call edge must be an array")?;
        let [from, to] = values.as_slice() else {
            return Err(format!("{key} edge requires two symbols"));
        };
        target.insert((
            from.as_str().ok_or("caller identity")?.to_owned(),
            to.as_str().ok_or("callee identity")?.to_owned(),
        ));
    }
    Ok(())
}

/// `namespace:` declared in a module's `module.yaml` — the package the manifest's
/// relative component names resolve against.
fn module_namespace(root: &Path, module: &str) -> Result<String, String> {
    let yaml_path = root.join(format!("apps/android/{module}/module.yaml"));
    let yaml = fs::read_to_string(&yaml_path)
        .map_err(|error| format!("{}: {error}", yaml_path.display()))?;
    for line in yaml.lines() {
        if let Some(value) = line.trim().strip_prefix("namespace:") {
            return Ok(value.trim().to_owned());
        }
    }
    Err(format!("{} declares no namespace:", yaml_path.display()))
}

/// Length of the leading XML name (`[A-Za-z0-9_.:-]` after the first char; the
/// scanner only enters here at a name start).
fn xml_name_len(text: &str) -> usize {
    text.find(|c: char| !(c.is_alphanumeric() || matches!(c, '_' | '-' | '.' | ':')))
        .unwrap_or(text.len())
}

/// One parsed start-tag: full element name (so `<activity-alias` is never an
/// `<activity`), `(key, value)` attributes in document order, the self-closing
/// (`/>`) flag, and the remainder of the document.
type ElementStart<'a> = (String, Vec<(String, String)>, bool, &'a str);

/// Parses one element start-tag at `rest` (pointing just past `<`). Attribute
/// values are quote-delimited, so `>` inside a value cannot desync the tag
/// walk; malformed markup fails closed.
fn scan_element_start<'a>(rest: &'a str, manifest_path: &Path) -> Result<ElementStart<'a>, String> {
    let malformed = |detail: &str| format!("{}: {detail}", manifest_path.display());
    let name_len = xml_name_len(rest);
    if name_len == 0 {
        return Err(malformed("stray `<` outside an element"));
    }
    let (element_name, mut rest) = rest.split_at(name_len);
    let name = element_name.to_owned();
    let mut attributes = Vec::new();
    loop {
        rest = rest.trim_start();
        if let Some(after) = rest.strip_prefix("/>") {
            return Ok((name, attributes, true, after));
        }
        if let Some(after) = rest.strip_prefix('>') {
            return Ok((name, attributes, false, after));
        }
        let key_len = rest
            .find(|c: char| c == '=' || c.is_whitespace() || c == '/' || c == '>')
            .unwrap_or(rest.len());
        if key_len == 0 {
            return Err(malformed("malformed attribute in element"));
        }
        let (key, after_key) = rest.split_at(key_len);
        let key = key.to_owned();
        rest = after_key.trim_start();
        let Some(after_eq) = rest.strip_prefix('=') else {
            return Err(malformed("attribute without a quoted value"));
        };
        rest = after_eq.trim_start();
        let Some(quote) = rest.chars().next().filter(|c| matches!(c, '"' | '\'')) else {
            return Err(malformed("attribute value must be quoted"));
        };
        let (_, after_quote) = rest.split_at(quote.len_utf8());
        let Some(value_len) = after_quote.find(quote) else {
            return Err(malformed("unterminated attribute value"));
        };
        let (value, after_value) = after_quote.split_at(value_len);
        let Some(after_value) = after_value.strip_prefix(quote) else {
            return Err(malformed("unterminated attribute value"));
        };
        attributes.push((key, value.to_owned()));
        rest = after_value;
    }
}

/// The manifest merger and the platform bind attributes by *namespace URI*,
/// not by the literal prefix name: `xmlns:t="…/tools"` makes `t:node="remove"`
/// the same merge directive as `tools:node="remove"`. The two URIs that carry
/// semantics here:
const ANDROID_NS: &str = "http://schemas.android.com/apk/res/android";
const TOOLS_NS: &str = "http://schemas.android.com/tools";

/// Prefix scope for one element — the `xmlns:<prefix>="uri"` declarations it
/// carries, folded over the enclosing element's scope (namespace declarations
/// apply to the element's own attributes). The canonical `android`/`tools`
/// prefixes are seeded at the root so a manifest omitting the declarations
/// still resolves; a declared `xmlns:` can rebind them, exactly like the
/// merger reads the document.
fn element_scope(
    parent: &BTreeMap<String, String>,
    attributes: &[(String, String)],
) -> BTreeMap<String, String> {
    let mut scope = parent.clone();
    for (key, value) in attributes {
        if let Some(prefix) = key.strip_prefix("xmlns:") {
            scope.insert(prefix.to_owned(), value.clone());
        }
    }
    scope
}

/// One attribute value resolved through the namespace map: an `prefix:local`
/// key binds `uri` when `prefix` maps to `uri` in `scope`. A prefix that maps
/// to nothing — or to a different URI — is not the attribute the merger would
/// see.
fn attribute<'a>(
    attributes: &'a [(String, String)],
    scope: &BTreeMap<String, String>,
    uri: &str,
    local: &str,
) -> Option<&'a str> {
    attributes
        .iter()
        .find(|(name, _)| {
            let Some((prefix, local_name)) = name.split_once(':') else {
                return false;
            };
            local_name == local && scope.get(prefix).is_some_and(|bound| bound == uri)
        })
        .map(|(_, value)| value.as_str())
}

/// The `tools:node` merge directive resolved by URI. `remove` strips the
/// element (and its subtree) from the packaged manifest, so the platform never
/// instantiates it; `merge`/`replace`/`strict`/`merge-only-attributes` keep
/// it. Any other value (`removeAll` and friends) is merge semantics the gate
/// cannot model — surfaced rather than guessed.
fn merged_away(
    attributes: &[(String, String)],
    scope: &BTreeMap<String, String>,
    malformed: &dyn Fn(&str) -> String,
) -> Result<bool, String> {
    match attribute(attributes, scope, TOOLS_NS, "node") {
        None | Some("merge" | "replace" | "strict" | "merge-only-attributes") => Ok(false),
        Some("remove") => Ok(true),
        Some(other) => Err(malformed(&format!(
            "unmodeled merge directive tools:node=\"{other}\""
        ))),
    }
}

/// `android:enabled` — `"true"` instantiates, `"false"` disables (the
/// component, or a whole `<application>`'s children). Any other value —
/// including a `@bool/…` resource indirection — is an enabled state the gate
/// cannot prove; treated as not-instantiated rather than minted.
fn enabled(attributes: &[(String, String)], scope: &BTreeMap<String, String>) -> bool {
    match attribute(attributes, scope, ANDROID_NS, "enabled") {
        None | Some("true") => true,
        Some(_) => false,
    }
}

/// Advances `rest` past one non-element markup construct — a `<!-- -->`
/// comment, `<? ?>` processing instruction or `<!...>` declaration — returning
/// the remainder, or `None` when `rest` does not open one. Malformed markup
/// fails closed: the gate cannot prove which components the platform
/// instantiates from a document it cannot read.
fn skip_markup_trivia<'a>(
    rest: &'a str,
    malformed: &dyn Fn(&str) -> String,
) -> Result<Option<&'a str>, String> {
    if let Some(after) = rest.strip_prefix("!--") {
        let Some(end) = after.find("-->") else {
            return Err(malformed("unterminated XML comment"));
        };
        return Ok(Some(after.split_at(end + 3).1));
    }
    if rest.starts_with('?') {
        let Some(end) = rest.find("?>") else {
            return Err(malformed("unterminated processing instruction"));
        };
        return Ok(Some(rest.split_at(end + 2).1));
    }
    if rest.starts_with('!') {
        // `<!DOCTYPE ...>`/`<![CDATA[ ... ]]>` — ends at a `>` outside any
        // `[...]` internal subset.
        let mut bracketed = 0_i32;
        let mut end = None;
        for (offset, c) in rest.char_indices() {
            match c {
                '[' => bracketed += 1,
                ']' => bracketed -= 1,
                '>' if bracketed == 0 => {
                    end = Some(offset);
                    break;
                }
                _ => {}
            }
        }
        let Some(end) = end else {
            return Err(malformed("unterminated `<!` declaration"));
        };
        return Ok(Some(rest.split_at(end + 1).1));
    }
    Ok(None)
}

/// Component classes the platform actually instantiates from a module
/// `AndroidManifest.xml`, resolved against the module's `namespace:` in
/// `module.yaml`. A module without a manifest declares none.
///
/// Manifest semantics, not element presence: only `activity`/`service`/
/// `receiver`/`provider` elements *directly inside* `<application>` register —
/// an `<activity>` sibling under `<manifest>` is ignored by the platform — and
/// `<application android:name>` itself instantiates the application class.
/// `tools:node="remove"` drops the element (and, on `<application>`, its
/// children) at merge; `android:enabled="false"` (on the component or the
/// application) means the platform never instantiates it.
///
/// The scan is element-aware rather than text-substring: `<!-- -->` comments,
/// `<? ?>` processing instructions, `<!...>` declarations and `</` end tags are
/// handled structurally, an element name is matched in full (`<activity-alias`
/// is not `<activity`), attribute values are quote-delimited so `>` inside a
/// value cannot desync the tag walk, and nesting is tracked on an element
/// stack — a mismatched or unclosed tag fails closed like any other malformed
/// markup.
fn manifest_components(root: &Path, module: &str) -> Result<BTreeSet<String>, String> {
    let manifest_path = root.join(format!("apps/android/{module}/src/AndroidManifest.xml"));
    if !manifest_path.is_file() {
        return Ok(BTreeSet::new());
    }
    let manifest = fs::read_to_string(&manifest_path)
        .map_err(|error| format!("{}: {error}", manifest_path.display()))?;
    let malformed = |detail: &str| format!("{}: {detail}", manifest_path.display());
    let mut namespace = String::new();
    let mut components = BTreeSet::new();
    let mut mint = |attributes: &[(String, String)], scope: &BTreeMap<String, String>| {
        mint_manifest_component(
            &mut components,
            &mut namespace,
            attributes,
            scope,
            root,
            module,
        )
    };
    let mut rest = manifest.as_str();
    // Canonical prefixes seeded like the merger reads them: a manifest that
    // declares no `xmlns:` still binds `android:`/`tools:` by convention; a
    // declared `xmlns:` rebinds the prefix for its subtree.
    let root_scope = BTreeMap::from([
        ("android".to_owned(), ANDROID_NS.to_owned()),
        ("tools".to_owned(), TOOLS_NS.to_owned()),
    ]);
    // Open-element stack: `(name, namespace scope, stripped)` — a component
    // registers only as a direct child of the `<application>` that itself sits
    // under `<manifest>`; the scope carries `xmlns:` bindings down the
    // ancestry (attributes bind by URI, not literal prefix) and `stripped`
    // carries `tools:node="remove"` down it — a `<manifest>`-level remove
    // drops the entire component set at merge.
    let mut stack: Vec<(String, BTreeMap<String, String>, bool)> = Vec::new();
    // The open `<application>` survives manifest merge and is enabled.
    let mut live_application = false;
    while let Some(open) = rest.find('<') {
        rest = rest.split_at(open + 1).1;
        if let Some(after) = skip_markup_trivia(rest, &malformed)? {
            rest = after;
            continue;
        }
        if let Some(tail) = rest.strip_prefix('/') {
            let name_len = xml_name_len(tail);
            if name_len == 0 {
                return Err(malformed("malformed end tag"));
            }
            let (name, after_name) = tail.split_at(name_len);
            let Some(after) = after_name.trim_start().strip_prefix('>') else {
                return Err(malformed("malformed end tag"));
            };
            rest = after;
            if stack.last().map(|(open, _, _)| open.as_str()) != Some(name) {
                return Err(malformed("mismatched end tag"));
            }
            stack.pop();
            if name == "application" {
                live_application = false;
            }
            continue;
        }
        let (name, attributes, self_closing, after) = scan_element_start(rest, &manifest_path)?;
        rest = after;
        let (parent_scope, parent_stripped) = stack
            .last()
            .map_or((&root_scope, false), |(_, scope, stripped)| {
                (scope, *stripped)
            });
        let scope = element_scope(parent_scope, &attributes);
        let stripped = parent_stripped || merged_away(&attributes, &scope, &malformed)?;
        let under_manifest = stack
            .iter()
            .map(|(open, _, _)| open.as_str())
            .eq(["manifest"]);
        let under_application = stack
            .iter()
            .map(|(open, _, _)| open.as_str())
            .eq(["manifest", "application"]);
        if name == "application" && under_manifest {
            // The application element itself instantiates its
            // `android:name` class — unless it is merged away or disabled, in
            // which case its children are dropped/disabled identically.
            live_application = !stripped && enabled(&attributes, &scope);
            if live_application {
                mint(&attributes, &scope)?;
            }
        } else if matches!(
            name.as_str(),
            "activity" | "service" | "receiver" | "provider"
        ) && live_application
            && under_application
            && !stripped
            && enabled(&attributes, &scope)
        {
            mint(&attributes, &scope)?;
        }
        if self_closing {
            if name == "application" {
                live_application = false;
            }
        } else {
            stack.push((name, scope, stripped));
        }
    }
    if !stack.is_empty() {
        return Err(malformed("unclosed element at end of manifest"));
    }
    Ok(components)
}

/// Mints one manifest-registered component name into `components`: the
/// `android:name` value resolved through the element's namespace scope, made
/// fully-qualified against the module `namespace:` when relative. Absent or
/// empty names mint nothing (a nameless component registers no class).
fn mint_manifest_component(
    components: &mut BTreeSet<String>,
    namespace: &mut String,
    attributes: &[(String, String)],
    scope: &BTreeMap<String, String>,
    root: &Path,
    module: &str,
) -> Result<(), String> {
    let Some(value) = attribute(attributes, scope, ANDROID_NS, "name") else {
        return Ok(());
    };
    if value.is_empty() {
        return Ok(());
    }
    let component = if value.contains('.') && !value.starts_with('.') {
        value.to_owned()
    } else {
        if namespace.is_empty() {
            *namespace = module_namespace(root, module)?;
        }
        format!("{namespace}.{}", value.trim_start_matches('.'))
    };
    components.insert(component);
    Ok(())
}

fn read_json(path: &Path) -> Result<serde_json::Value, String> {
    serde_json::from_slice(&fs::read(path).map_err(|error| format!("{}: {error}", path.display()))?)
        .map_err(|error| error.to_string())
}

fn string<'a>(value: &'a serde_json::Value, key: &str) -> Result<&'a str, String> {
    value
        .get(key)
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| format!("missing string {key}"))
}

fn strings(value: &serde_json::Value, key: &str) -> Result<BTreeSet<String>, String> {
    value
        .get(key)
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| format!("missing list {key}"))?
        .iter()
        .map(|value| {
            value
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| format!("invalid symbol in {key}"))
        })
        .collect()
}

/// Loads schema-3 resolved symbol facts for every production source file, plus the
/// manifest-declared components each module's `AndroidManifest.xml` registers.
///
/// # Errors
/// Fails closed on missing/stale facts, an unsupported schema, a facts file that does
/// not cover every production `.kt` source exactly, an installer source whose
/// reflective `DataModulesKt` load no longer exists, or a root that is not a declared
/// holder class.
pub fn load_graph(
    root: &Path,
    facts_root: &Path,
) -> Result<(BTreeSet<String>, ResolvedGraph), String> {
    let mut graph = ResolvedGraph::default();
    let mut generated = None;
    for module in ["app", "data", "domain", "ui-components"] {
        graph.manifest.extend(manifest_components(root, module)?);
        let index = read_json(&facts_root.join(format!("index-{module}.json")))?;
        let directory = Path::new(string(&index, "directory")?);
        let mut observed = BTreeSet::new();
        for entry in fs::read_dir(directory).map_err(|error| error.to_string())? {
            let path = entry.map_err(|error| error.to_string())?.path();
            if path.extension().is_none_or(|extension| extension != "json") {
                continue;
            }
            let facts = read_json(&path)?;
            if facts
                .get("schema_version")
                .and_then(serde_json::Value::as_u64)
                != Some(3)
            {
                return Err("unsupported resolved graph schema".to_owned());
            }
            let source = Path::new(string(&facts, "path")?);
            let bytes = fs::read(source).map_err(|error| error.to_string())?;
            if format!("{:x}", Sha256::digest(bytes)) != string(&facts, "source_digest")? {
                return Err(format!(
                    "stale resolved symbol facts for {}",
                    source.display()
                ));
            }
            let declarations = strings(&facts, "declarations")?;
            if path
                .file_name()
                .is_some_and(|name| name == "generated.json")
            {
                if let Some(previous) = &generated
                    && previous != &declarations
                {
                    return Err("generated declarations disagree across modules".to_owned());
                }
                generated = Some(declarations);
                continue;
            }
            if !source.starts_with(root.join(format!("apps/android/{module}/src"))) {
                return Err(format!("non-production symbol input {}", source.display()));
            }
            observed.insert(source.to_owned());
            graph.declarations.extend(declarations);
            graph.classes.extend(strings(&facts, "classes")?);
            graph.roots.extend(strings(&facts, "roots")?);
            read_pairs(&facts, "edges", &mut graph.edges)?;
            read_pairs(&facts, "references", &mut graph.references)?;
        }
        let expected: BTreeSet<_> =
            super::source_files(root, &format!("apps/android/{module}/src"))?
                .into_iter()
                .filter(|path| path.extension().is_some_and(|extension| extension == "kt"))
                .collect();
        if observed != expected {
            return Err(format!(
                "incomplete resolved call graph for {module}: missing {:?}, unexpected {:?}",
                expected.difference(&observed).collect::<Vec<_>>(),
                observed.difference(&expected).collect::<Vec<_>>()
            ));
        }
    }
    install_app_data_koin_runtime_edge(root, &mut graph)?;
    if !graph.roots.is_subset(&graph.classes) {
        return Err(
            "resolved graph root is not a declared holder class — member-level entry \
             points are invalid"
                .to_owned(),
        );
    }
    Ok((
        generated.ok_or("generated Kotlin declaration catalogue missing")?,
        graph,
    ))
}
