use serde::{Deserialize, Serialize};

macro_rules! binding_id {
    ($(#[$meta:meta])* $name:ident) => {
        $(#[$meta])*
        #[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
        #[serde(transparent)]
        pub struct $name(u32);

        impl $name {
            /// Wraps a numeric id without revalidating it.
            ///
            /// The number is meaningful only inside the
            /// [`Bindings`](crate::Bindings) it was allocated for. Carrying an
            /// id from one contract into another produces a nominally typed
            /// but functionally invalid value.
            pub const fn from_raw(raw: u32) -> Self {
                Self(raw)
            }

            /// Returns the underlying numeric value.
            pub const fn raw(self) -> u32 {
                self.0
            }
        }
    };
}

binding_id! {
    /// Identity of a record declaration inside one binding contract.
    RecordId
}

binding_id! {
    /// Identity of an enum declaration inside one binding contract.
    EnumId
}

binding_id! {
    /// Identity of a class declaration inside one binding contract.
    ClassId
}

binding_id! {
    /// Identity of a free function declaration inside one binding contract.
    FunctionId
}

binding_id! {
    /// Identity of a method inside its owning declaration.
    MethodId
}

binding_id! {
    /// Identity of an initializer inside its owning declaration.
    InitializerId
}

binding_id! {
    /// Identity of a callback declaration inside one binding contract.
    CallbackId
}

binding_id! {
    /// Identity of a stream declaration inside one binding contract.
    StreamId
}

binding_id! {
    /// Identity of a constant declaration inside one binding contract.
    ConstantId
}

binding_id! {
    /// Identity of a custom type declaration inside one binding contract.
    CustomTypeId
}

binding_id! {
    /// Identity of a native symbol inside one binding contract.
    SymbolId
}

/// Identity of any top-level declaration, regardless of family.
///
/// A `DeclarationId` answers "which declaration in this contract" without losing
/// track of which kind of declaration it is. Two values are equal only when
/// both their family and their typed id match, so a record id and a function
/// id with the same numeric value never collide.
///
/// # Example
///
/// `DeclarationId::Record(RecordId::from_raw(0))` and
/// `DeclarationId::Function(FunctionId::from_raw(0))` share the same raw value but
/// are distinct `DeclarationId`s; both can coexist in the same contract.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[non_exhaustive]
pub enum DeclarationId {
    /// Record id.
    Record(RecordId),
    /// Enum id.
    Enum(EnumId),
    /// Class id.
    Class(ClassId),
    /// Function id.
    Function(FunctionId),
    /// Callback id.
    Callback(CallbackId),
    /// Stream id.
    Stream(StreamId),
    /// Constant id.
    Constant(ConstantId),
    /// Custom type id.
    CustomType(CustomTypeId),
}
