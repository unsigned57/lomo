//! JVM method return contracts built from C ABI return types.
//!
//! Callback vtable slots and closure trampolines call static JVM methods. After
//! the call returns, generated C must translate the Java value back into the C
//! shape Rust expects: no value, scalar value, byte buffer, direct record, or
//! callback handle.
//!
//! This module is the single place that maps a C return type into that JVM
//! return contract. It keeps failure values, method descriptors, and callback
//! handle construction aligned for callbacks and closures instead of letting
//! each caller invent its own return mapping.

use crate::{
    bridge::{
        c::{self, Identifier, TypeFragment},
        jni::{JniType, JvmMethodReturn},
    },
    core::{Error, Result},
};

const JNI_BRIDGE: &str = "jni";

impl JvmMethodReturn {
    /// Creates a JVM method return contract from one C ABI return type.
    pub fn from_c_type(ty: &c::Type, callbacks: &[c::Callback]) -> Result<Self> {
        match ty {
            c::Type::Void => Ok(Self::Void {
                c_type: TypeFragment::anonymous(ty)?,
            }),
            c::Type::Buffer => Ok(Self::Bytes {
                c_type: TypeFragment::anonymous(ty)?,
            }),
            c::Type::DirectRecord(_) => Ok(Self::Record {
                c_type: TypeFragment::anonymous(ty)?,
            }),
            c::Type::CallbackHandle(callback) => {
                let declaration = callbacks
                    .iter()
                    .find(|declaration| declaration.id() == *callback)
                    .ok_or(Error::BrokenBridgeContract {
                        bridge: JNI_BRIDGE,
                        invariant: "JVM callback handle return has no C callback declaration",
                    })?;
                Ok(Self::CallbackHandle {
                    c_type: TypeFragment::anonymous(ty)?,
                    create_handle: Identifier::parse(declaration.create_handle().name())?,
                })
            }
            ty @ (c::Type::Bool
            | c::Type::Int8
            | c::Type::Uint8
            | c::Type::Int16
            | c::Type::Uint16
            | c::Type::Int32
            | c::Type::Uint32
            | c::Type::Int64
            | c::Type::Uint64
            | c::Type::SignedPointerWidth
            | c::Type::PointerWidth
            | c::Type::Float32
            | c::Type::Float64
            | c::Type::FutureHandle
            | c::Type::StreamPollResult
            | c::Type::WaitResult
            | c::Type::ConstPointer(_)
            | c::Type::MutPointer(_)
            | c::Type::FunctionPointer { .. }
            | c::Type::CStyleEnum { .. }) => Ok(Self::Value {
                c_type: TypeFragment::anonymous(ty)?,
                jni_type: JniType::from_c_type(ty)?,
            }),
            c::Type::Status | c::Type::String | c::Type::Span | c::Type::Named(_) => {
                Err(Error::UnsupportedBridge {
                    bridge: JNI_BRIDGE,
                    shape: "JVM method return",
                })
            }
        }
    }

    /// Creates the return contract for closure-return callback methods.
    pub fn closure_status() -> Result<Self> {
        Ok(Self::Closure {
            c_type: TypeFragment::anonymous(&c::Type::Status)?,
        })
    }
}
