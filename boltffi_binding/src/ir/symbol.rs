use serde::{Deserialize, Serialize};
use std::collections::HashSet;

use crate::{BindingError, BindingErrorKind, Decl, Surface, SymbolId};

/// A linker-visible name as it appears in the compiled Rust artifact.
///
/// Foreign code calls these names through whatever FFI mechanism the
/// target uses: P/Invoke for .NET, `dlsym`/`dlopen` for POSIX, JNI for the
/// JVM, `dart:ffi` for Dart, and so on. The constructor enforces
/// C-identifier shape so foreign linkers can resolve the name without
/// escaping or quoting.
///
/// # Example
///
/// `boltffi_demo_add` is a valid `SymbolName`. `1bad_name`, `with-dash`,
/// and the empty string are not.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SymbolName(String);

impl SymbolName {
    pub(crate) fn parse(name: impl Into<String>) -> Result<Self, BindingError> {
        let name = name.into();

        if IdentifierText::new(&name).is_valid() {
            Ok(Self(name))
        } else {
            Err(BindingError::new(BindingErrorKind::InvalidSymbolName(name)))
        }
    }

    /// Returns the exported name.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A name in the compiled Rust artifact's symbol table.
///
/// The lowerer picks one `NativeSymbol` for every callable surface the
/// contract exposes (free functions, methods, initializers, accessors, the
/// helper symbols around an async call). Foreign code invokes the name
/// through the target language's FFI mechanism.
///
/// The id is stable inside the table even if a renaming pass changes the
/// spelling later, and even if two declarations want to share the same
/// exported name. Code that needs to refer to a callable across passes
/// uses the id; code that actually invokes the callable uses the name.
///
/// # Example
///
/// A Rust function `fn add(a: i32, b: i32) -> i32` exported as
/// `boltffi_demo_add` is represented by a `NativeSymbol` carrying that
/// name and the id the table assigned.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct NativeSymbol {
    id: SymbolId,
    name: SymbolName,
}

impl NativeSymbol {
    pub(crate) fn new(id: SymbolId, name: SymbolName) -> Self {
        Self { id, name }
    }

    /// Returns the symbol id.
    pub const fn id(&self) -> SymbolId {
        self.id
    }

    /// Returns the symbol name.
    pub fn name(&self) -> &SymbolName {
        &self.name
    }
}

/// The full set of native symbols a [`Bindings`](crate::Bindings) needs at
/// link time.
///
/// Listing every symbol in one place enables two things: ahead-of-time
/// verification that the compiled Rust artifact actually exports them all,
/// and id-based lookup without walking every declaration.
///
/// A held table is consistent: ids are unique, names are unique, and every
/// name is callable.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct NativeSymbolTable {
    symbols: Vec<NativeSymbol>,
}

impl NativeSymbolTable {
    pub(crate) fn from_symbols(symbols: Vec<NativeSymbol>) -> Result<Self, BindingError> {
        let table = Self { symbols };
        table.validate()?;
        Ok(table)
    }

    /// Builds a table from the deduplicated union of every native
    /// symbol the supplied declarations reference.
    ///
    /// Symbols are inserted in first-seen order so the table layout is
    /// deterministic given a stable declaration order.
    pub(crate) fn from_decls<S: Surface>(decls: &[Decl<S>]) -> Result<Self, BindingError> {
        let mut seen: HashSet<&NativeSymbol> = HashSet::new();
        let mut ordered: Vec<NativeSymbol> = Vec::new();
        for symbol in decls.iter().flat_map(Decl::native_symbols) {
            if seen.insert(symbol) {
                ordered.push(symbol.clone());
            }
        }
        Self::from_symbols(ordered)
    }

    /// Returns the symbols in registration order.
    pub fn symbols(&self) -> &[NativeSymbol] {
        &self.symbols
    }

    /// Returns the symbol with the given id, or `None`.
    pub fn find(&self, id: SymbolId) -> Option<&NativeSymbol> {
        self.symbols.iter().find(|symbol| symbol.id == id)
    }

    /// Returns `Ok` when every name is callable, every id is unique, and
    /// every name is unique. Returns the first failed invariant otherwise.
    pub fn validate(&self) -> Result<(), BindingError> {
        self.validate_names()?;
        self.validate_unique_ids()?;
        self.validate_unique_names()
    }

    fn validate_names(&self) -> Result<(), BindingError> {
        self.symbols
            .iter()
            .map(NativeSymbol::name)
            .find(|name| !IdentifierText::new(name.as_str()).is_valid())
            .map_or(Ok(()), |name| {
                Err(BindingError::new(BindingErrorKind::InvalidSymbolName(
                    name.as_str().to_owned(),
                )))
            })
    }

    fn validate_unique_ids(&self) -> Result<(), BindingError> {
        self.symbols
            .iter()
            .map(NativeSymbol::id)
            .try_fold(HashSet::new(), |mut seen, symbol_id| {
                if seen.insert(symbol_id) {
                    Ok(seen)
                } else {
                    Err(BindingError::new(BindingErrorKind::DuplicateSymbolId(
                        symbol_id,
                    )))
                }
            })
            .map(|_| ())
    }

    fn validate_unique_names(&self) -> Result<(), BindingError> {
        self.symbols
            .iter()
            .map(|symbol| symbol.name().as_str().to_owned())
            .try_fold(HashSet::new(), |mut seen, symbol_name| {
                if seen.insert(symbol_name.clone()) {
                    Ok(seen)
                } else {
                    Err(BindingError::new(BindingErrorKind::DuplicateSymbolName(
                        symbol_name,
                    )))
                }
            })
            .map(|_| ())
    }
}

struct IdentifierText<'text> {
    text: &'text str,
}

impl<'text> IdentifierText<'text> {
    const fn new(text: &'text str) -> Self {
        Self { text }
    }

    fn is_valid(&self) -> bool {
        let mut characters = self.text.chars();
        characters
            .next()
            .is_some_and(|character| character == '_' || character.is_ascii_alphabetic())
            && characters.all(|character| character == '_' || character.is_ascii_alphanumeric())
    }
}

/// The name of a function-pointer field in a generated vtable struct.
///
/// Foreign code provides the function pointer; Rust calls through it.
/// Distinct from [`NativeSymbol`] because a vtable slot is not a
/// linker-visible symbol and validates as a Rust struct-field
/// identifier rather than a C identifier.
///
/// # Example
///
/// A callback trait with a `fn handle(self, event: Event)` method
/// produces a vtable struct whose corresponding field is named
/// `handle`; that field name is held as a `VTableSlot`.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(transparent)]
pub struct VTableSlot(String);

impl VTableSlot {
    pub(crate) fn parse(name: impl Into<String>) -> Result<Self, BindingError> {
        let name = name.into();

        if IdentifierText::new(&name).is_valid() {
            Ok(Self(name))
        } else {
            Err(BindingError::new(BindingErrorKind::InvalidVTableSlot(name)))
        }
    }

    /// Returns the slot name.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// The module name in a wasm module's import section.
///
/// Wasm imports are addressed as a `(module, name)` pair. For boltffi
/// bindings the module is typically `"env"`, but the type captures it
/// explicitly so the renderer never invents a module name.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ImportModule(String);

impl ImportModule {
    pub(crate) fn parse(name: impl Into<String>) -> Result<Self, BindingError> {
        let name = name.into();
        if name.is_empty() {
            Err(BindingError::new(BindingErrorKind::InvalidImportModule(
                name,
            )))
        } else {
            Ok(Self(name))
        }
    }

    /// Returns the module name.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A function imported by a wasm module.
///
/// Pairs the wasm import module with the function name the host
/// resolves at link time. Distinct from [`NativeSymbol`] because wasm
/// imports are not linker symbols, and distinct from [`VTableSlot`]
/// because they are not struct fields. The name is typically a
/// generated identifier such as `__boltffi_callback_adder_add`.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct ImportSymbol {
    module: ImportModule,
    name: SymbolName,
}

impl ImportSymbol {
    pub(crate) fn new(module: ImportModule, name: SymbolName) -> Self {
        Self { module, name }
    }

    /// Returns the import module.
    pub fn module(&self) -> &ImportModule {
        &self.module
    }

    /// Returns the imported function name.
    pub fn name(&self) -> &SymbolName {
        &self.name
    }
}
