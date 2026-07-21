use std::fmt;

use serde::{Deserialize, Serialize};

macro_rules! define_id {
    ($name:ident, $doc:expr) => {
        #[doc = $doc]
        ///
        /// IDs are stable names for declarations inside a [`SourceContract`](crate::SourceContract).
        /// They are derived from canonical Rust paths, which lets separate
        /// metadata entries refer to the same declaration without relying on
        /// list position.
        #[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
        pub struct $name(String);

        impl $name {
            /// Builds an ID from a scanner-produced canonical path.
            ///
            /// The `value` parameter is stored exactly as supplied. Callers
            /// should pass the canonical Rust path they use for declaration
            /// identity.
            ///
            /// Returns a declaration ID suitable for references inside the
            /// source contract.
            pub fn new(value: impl Into<String>) -> Self {
                Self(value.into())
            }

            /// Returns the canonical ID text.
            ///
            /// The returned value is the identity string used inside the source
            /// contract.
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str(&self.0)
            }
        }

        impl From<String> for $name {
            fn from(value: String) -> Self {
                Self::new(value)
            }
        }

        impl From<&str> for $name {
            fn from(value: &str) -> Self {
                Self::new(value)
            }
        }
    };
}

define_id!(
    RecordId,
    "Identifies a record declaration in the source contract."
);
define_id!(
    EnumId,
    "Identifies an enum declaration in the source contract."
);
define_id!(
    FunctionId,
    "Identifies a free function exported by the source contract."
);
define_id!(
    MethodId,
    "Identifies a method-like callable attached to a record, enum, class, or callback."
);
define_id!(
    ClassId,
    "Identifies an exported class-style object declaration."
);
define_id!(
    TraitId,
    "Identifies a trait declaration exposed to the FFI surface as a callback target."
);
define_id!(
    StreamId,
    "Identifies a stream declaration exposed by a class or by a future top-level stream export."
);
define_id!(
    ConstantId,
    "Identifies a constant declaration exported into the source contract."
);
define_id!(
    CustomTypeId,
    "Identifies a custom type declaration and its conversion hooks."
);

/// Identity of any top-level declaration in a source contract.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[non_exhaustive]
pub enum DeclarationId {
    /// Record declaration id.
    Record(RecordId),
    /// Enum declaration id.
    Enum(EnumId),
    /// Class declaration id.
    Class(ClassId),
    /// Function declaration id.
    Function(FunctionId),
    /// Callback trait declaration id.
    Trait(TraitId),
    /// Stream declaration id.
    Stream(StreamId),
    /// Constant declaration id.
    Constant(ConstantId),
    /// Custom type declaration id.
    CustomType(CustomTypeId),
}
