use boltffi_binding::{CallbackId, HandleTarget, Primitive, native};

use crate::core::{Error, Result};

use super::{C_BRIDGE_LAYER, Identifier};

/// A C ABI type.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub enum Type {
    /// `void`.
    Void,
    /// `bool`.
    Bool,
    /// `int8_t`.
    Int8,
    /// `uint8_t`.
    Uint8,
    /// `int16_t`.
    Int16,
    /// `uint16_t`.
    Uint16,
    /// `int32_t`.
    Int32,
    /// `uint32_t`.
    Uint32,
    /// `int64_t`.
    Int64,
    /// `uint64_t`.
    Uint64,
    /// `float`.
    Float32,
    /// `double`.
    Float64,
    /// `intptr_t`.
    SignedPointerWidth,
    /// `uintptr_t`.
    PointerWidth,
    /// `FfiStatus`.
    Status,
    /// `FfiBuf_u8`.
    Buffer,
    /// `FfiString`.
    String,
    /// `FfiSpan`.
    Span,
    /// `RustFutureHandle`.
    FutureHandle,
    /// `StreamPollResult`.
    StreamPollResult,
    /// `WaitResult`.
    WaitResult,
    /// `BoltFFICallbackHandle`.
    CallbackHandle(CallbackId),
    /// A generated named C type.
    Named(Identifier),
    /// A generated direct record typedef passed by value.
    DirectRecord(Identifier),
    /// A generated C-style enum typedef passed by value.
    CStyleEnum {
        /// Generated enum typedef name.
        name: Identifier,
        /// Integer representation used by the typedef.
        repr: Box<Type>,
    },
    /// Pointer to const data.
    ConstPointer(Box<Type>),
    /// Pointer to mutable data.
    MutPointer(Box<Type>),
    /// C function pointer.
    FunctionPointer {
        /// Function pointer return type.
        returns: Box<Type>,
        /// Function pointer parameters.
        params: Vec<Type>,
    },
}

impl Type {
    /// Creates a generated named C type.
    pub fn named(name: impl Into<String>) -> Result<Self> {
        Identifier::parse(name).map(Self::Named)
    }

    /// Creates the C ABI type for a primitive scalar.
    pub fn primitive(primitive: Primitive) -> Result<Self> {
        match primitive {
            Primitive::Bool => Ok(Self::Bool),
            Primitive::I8 => Ok(Self::Int8),
            Primitive::U8 => Ok(Self::Uint8),
            Primitive::I16 => Ok(Self::Int16),
            Primitive::U16 => Ok(Self::Uint16),
            Primitive::I32 => Ok(Self::Int32),
            Primitive::U32 => Ok(Self::Uint32),
            Primitive::I64 => Ok(Self::Int64),
            Primitive::U64 => Ok(Self::Uint64),
            Primitive::ISize => Ok(Self::SignedPointerWidth),
            Primitive::USize => Ok(Self::PointerWidth),
            Primitive::F32 => Ok(Self::Float32),
            Primitive::F64 => Ok(Self::Float64),
            _ => Err(Error::UnexpectedBindingShape {
                layer: C_BRIDGE_LAYER,
                shape: "unknown primitive",
            }),
        }
    }

    /// Creates the C ABI type for a native handle carrier.
    pub fn handle_carrier(carrier: native::HandleCarrier) -> Result<Self> {
        match carrier {
            native::HandleCarrier::U64 => Ok(Self::Uint64),
            native::HandleCarrier::USize => Ok(Self::PointerWidth),
            native::HandleCarrier::CallbackHandle => Err(Error::UnexpectedBindingShape {
                layer: C_BRIDGE_LAYER,
                shape: "callback handle carrier without target",
            }),
            _ => Err(Error::UnexpectedBindingShape {
                layer: C_BRIDGE_LAYER,
                shape: "unknown native handle carrier",
            }),
        }
    }

    /// Creates the C ABI type for a typed native handle target.
    pub fn handle_target(target: &HandleTarget, carrier: native::HandleCarrier) -> Result<Self> {
        match (target, carrier) {
            (HandleTarget::Callback(callback), native::HandleCarrier::CallbackHandle) => {
                Ok(Self::CallbackHandle(*callback))
            }
            (HandleTarget::Callback(_), _) => Err(Error::UnexpectedBindingShape {
                layer: C_BRIDGE_LAYER,
                shape: "callback handle carrier",
            }),
            (_, carrier) => Self::handle_carrier(carrier),
        }
    }
}
