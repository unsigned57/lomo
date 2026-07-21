//! Return values from generated `Java_*` native methods.
//!
//! Java rarely receives the raw C bridge return unchanged. Encoded payloads and
//! direct records become `jbyteArray`, callback handles become opaque numeric
//! tokens, fallible void calls become status checks, and async starts return a
//! future handle that is completed by separate protocol methods.
//!
//! This module owns the native-method return contract. It answers the questions
//! the method template actually needs: which JNI type to declare, which C result
//! local to allocate, whether direct-record bytes must be copied out, and which
//! expression leaves the `Java_*` method. Those answers come from the C bridge
//! return shape, not from reinterpreting the original Rust return type.

use crate::{
    bridge::{
        c::{self, Expression, Identifier, ReturnChannel, TypeFragment},
        jni::{BytesWriteback, CallbackReturn, RecordValue, ScalarReturn},
    },
    core::{Error, Result},
};

const JNI_BRIDGE: &str = "jni";

/// JNI return behavior for one native method.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub enum NativeReturn {
    /// The C function returns `void`.
    Void,
    /// The C function returns a scalar value directly.
    Value(ScalarReturn),
    /// The C function returns an owned BoltFFI byte buffer.
    Bytes,
    /// The C function returns a direct record by value.
    Record(RecordValue),
    /// The C function returns a callback handle by value.
    Callback(CallbackReturn),
    /// The C function returns `FfiStatus` and the JNI method returns `void`.
    Status,
    /// The C function returns `FfiStatus` and writes an encoded mutation buffer.
    StatusWriteback(BytesWriteback),
    /// The C function returns `FfiStatus` and writes the success value to `return_out`.
    StatusValue(SuccessOutReturn),
    /// The C function returns an encoded error buffer and has no success value.
    EncodedError,
    /// The C function returns an encoded error buffer and writes success to `return_out`.
    EncodedErrorValue(EncodedErrorReturn),
}

impl NativeReturn {
    /// Returns the JNI method return type as C syntax.
    pub fn jni_type(&self) -> TypeFragment {
        match self {
            Self::Void | Self::Status | Self::EncodedError => TypeFragment::new("void"),
            Self::StatusWriteback(_) => TypeFragment::new("jbyteArray"),
            Self::Value(scalar) => scalar.jni_type().as_type_fragment(),
            Self::Callback(callback) => callback.jni_type(),
            Self::Bytes | Self::Record(_) => TypeFragment::new("jbyteArray"),
            Self::StatusValue(value) => value.jni_type(),
            Self::EncodedErrorValue(value) => value.jni_type(),
        }
    }

    /// Returns the temporary C result type used inside the JNI body.
    pub fn c_result_type(&self) -> Result<TypeFragment> {
        match self {
            Self::Void => Ok(TypeFragment::new("void")),
            Self::Status | Self::StatusWriteback(_) => TypeFragment::anonymous(&c::Type::Status),
            Self::Value(scalar) => scalar.c_result_type(),
            Self::Bytes | Self::EncodedError => TypeFragment::anonymous(&c::Type::Buffer),
            Self::Record(record) => Ok(record.c_type_fragment()),
            Self::Callback(callback) => callback.c_result_type(),
            Self::StatusValue(_) => TypeFragment::anonymous(&c::Type::Status),
            Self::EncodedErrorValue(_) => TypeFragment::anonymous(&c::Type::Buffer),
        }
    }

    /// Returns the expression returned from the JNI method for scalar values.
    pub fn return_expression(&self, value: Expression) -> Result<Expression> {
        match self {
            Self::Value(scalar) => Ok(scalar.return_expression(value)),
            Self::Callback(callback) => callback.return_expression(value),
            Self::StatusWriteback(writeback) => {
                Ok(Expression::identifier(writeback.local().clone()))
            }
            Self::Void | Self::Bytes | Self::Record(_) | Self::Status | Self::EncodedError => {
                Ok(value)
            }
            Self::StatusValue(value) => value.return_expression(),
            Self::EncodedErrorValue(value) => value.return_expression(),
        }
    }

    /// Returns direct-record return details when this return carries a record.
    pub fn record(&self) -> Option<&RecordValue> {
        match self {
            Self::Void
            | Self::Value(_)
            | Self::Bytes
            | Self::Callback(_)
            | Self::Status
            | Self::StatusWriteback(_)
            | Self::EncodedError => None,
            Self::Record(record) => Some(record),
            Self::StatusValue(value) => value.record(),
            Self::EncodedErrorValue(value) => value.record(),
        }
    }

    /// Returns whether the JVM method returns an owned byte array.
    pub fn is_bytes(&self) -> bool {
        match self {
            Self::Bytes | Self::StatusWriteback(_) => true,
            Self::StatusValue(value) => value.is_bytes(),
            Self::EncodedErrorValue(value) => value.is_bytes(),
            Self::Void
            | Self::Value(_)
            | Self::Record(_)
            | Self::Callback(_)
            | Self::Status
            | Self::EncodedError => false,
        }
    }

    /// Returns whether the JVM method returns a direct-record byte array.
    pub fn is_record(&self) -> bool {
        self.record().is_some()
    }

    /// Returns whether the JVM method return needs a boolean cast.
    pub fn is_boolean(&self) -> bool {
        match self {
            Self::Value(scalar) => scalar.jni_type().is_boolean(),
            Self::StatusValue(value) => value.is_boolean(),
            Self::EncodedErrorValue(value) => value.is_boolean(),
            Self::Void
            | Self::Bytes
            | Self::Record(_)
            | Self::Callback(_)
            | Self::Status
            | Self::EncodedError
            | Self::StatusWriteback(_) => false,
        }
    }

    /// Returns whether this return carries a callback handle.
    pub fn is_callback(&self) -> bool {
        match self {
            Self::Callback(_) => true,
            Self::StatusValue(value) => value.is_callback(),
            Self::EncodedErrorValue(value) => value.is_callback(),
            Self::Void
            | Self::Value(_)
            | Self::Bytes
            | Self::Record(_)
            | Self::Status
            | Self::EncodedError
            | Self::StatusWriteback(_) => false,
        }
    }

    /// Returns the success value written through `return_out`.
    pub fn success_out(&self) -> Option<&SuccessOutReturn> {
        match self {
            Self::Void
            | Self::Value(_)
            | Self::Bytes
            | Self::Record(_)
            | Self::Callback(_)
            | Self::Status
            | Self::StatusWriteback(_)
            | Self::EncodedError => None,
            Self::StatusValue(value) => Some(value),
            Self::EncodedErrorValue(value) => Some(value.success()),
        }
    }

    /// Returns whether the C return slot carries an encoded error buffer.
    pub fn checks_error_buffer(&self) -> bool {
        matches!(self, Self::EncodedError | Self::EncodedErrorValue(_))
    }

    /// Creates the JNI return behavior for a C ABI return type.
    pub fn from_c_type(ty: &c::Type) -> Result<Self> {
        if let Some(record) = RecordValue::from_c_type(ty) {
            return Ok(Self::Record(record));
        }
        if let Some(callback) = CallbackReturn::from_c_type(ty) {
            return Ok(Self::Callback(callback));
        }
        match ty {
            c::Type::Void => Ok(Self::Void),
            c::Type::Status => Ok(Self::Status),
            c::Type::Buffer => Ok(Self::Bytes),
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
            | c::Type::CStyleEnum { .. }) => ScalarReturn::from_c_type(ty).map(Self::Value),
            c::Type::String | c::Type::Span | c::Type::Named(_) => Err(Error::UnsupportedBridge {
                bridge: JNI_BRIDGE,
                shape: "native method return",
            }),
            c::Type::CallbackHandle(_) | c::Type::DirectRecord(_) => {
                Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "native return should have been handled before scalar mapping",
                })
            }
        }
    }

    /// Creates the JNI return behavior for a complete C ABI function.
    pub fn from_c_function(function: &c::Function) -> Result<Self> {
        let status = Self::from_c_type(function.returns())?;
        let writeback = function
            .parameter_groups()
            .iter()
            .filter_map(|group| match group {
                c::ParameterGroup::EncodedWriteback(writeback) => Some(writeback),
                _ => None,
            })
            .map(BytesWriteback::from_c_writeback)
            .collect::<Result<Vec<_>>>()?;
        let return_out = function
            .parameter_groups()
            .iter()
            .filter_map(|group| match group {
                c::ParameterGroup::SuccessOut(index) => Some(function.parameter(*index)),
                _ => None,
            })
            .map(SuccessOutReturn::from_parameter)
            .collect::<Result<Vec<_>>>()?;

        if !writeback.is_empty() && !return_out.is_empty() {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "encoded writeback cannot share the JNI return slot",
            });
        }

        if !writeback.is_empty() {
            if writeback.len() != 1 {
                return Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "encoded writeback must be the only hidden mutation return",
                });
            }
            let [writeback]: [BytesWriteback; 1] =
                writeback
                    .try_into()
                    .map_err(|_| Error::BrokenBridgeContract {
                        bridge: JNI_BRIDGE,
                        invariant: "encoded writeback must be the only hidden mutation return",
                    })?;
            return match status {
                Self::Status => Ok(Self::StatusWriteback(writeback)),
                _ => Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "encoded writeback must pair with FfiStatus",
                }),
            };
        }

        if return_out.is_empty() {
            return match (function.return_channel(), status) {
                (ReturnChannel::EncodedError, Self::Bytes) => Ok(Self::EncodedError),
                (ReturnChannel::EncodedError, _) => Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "encoded error return must use FfiBuf",
                }),
                (ReturnChannel::Value, status) => Ok(status),
            };
        }

        if return_out.len() != 1 {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "success out-pointer must be the only hidden return parameter",
            });
        }

        let [success]: [SuccessOutReturn; 1] =
            return_out
                .try_into()
                .map_err(|_| Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "success out-pointer must be the only hidden return parameter",
                })?;

        match (function.return_channel(), status) {
            (ReturnChannel::Value, Self::Status) => Ok(Self::StatusValue(success)),
            (ReturnChannel::EncodedError, Self::Bytes) => {
                Ok(Self::EncodedErrorValue(EncodedErrorReturn::new(success)))
            }
            (ReturnChannel::Value, _) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "success out-pointer must pair with FfiStatus",
            }),
            (ReturnChannel::EncodedError, _) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "encoded error return with success out-pointer must use FfiBuf",
            }),
        }
    }
}

/// JNI return behavior for an encoded-error fallible method.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub struct EncodedErrorReturn {
    success: SuccessOutReturn,
}

impl EncodedErrorReturn {
    /// Creates an encoded-error return from its success storage.
    pub fn new(success: SuccessOutReturn) -> Self {
        Self { success }
    }

    /// Returns the success value written by the lower bridge.
    pub fn success(&self) -> &SuccessOutReturn {
        &self.success
    }

    fn jni_type(&self) -> TypeFragment {
        self.success.jni_type()
    }

    fn return_expression(&self) -> Result<Expression> {
        self.success.return_expression()
    }

    fn record(&self) -> Option<&RecordValue> {
        self.success.record()
    }

    fn is_bytes(&self) -> bool {
        self.success.is_bytes()
    }

    fn is_boolean(&self) -> bool {
        self.success.is_boolean()
    }

    fn is_callback(&self) -> bool {
        self.success.is_callback()
    }
}

/// Success value storage for a fallible JNI method.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub struct SuccessOutReturn {
    local: Identifier,
    c_type: TypeFragment,
    value: Box<NativeReturn>,
}

impl SuccessOutReturn {
    /// Returns the local variable that receives the C success value.
    pub fn local(&self) -> &Identifier {
        &self.local
    }

    /// Returns the C type stored in the local success value.
    pub fn c_type(&self) -> &TypeFragment {
        &self.c_type
    }

    /// Returns the C argument passed to the lower bridge.
    pub fn argument(&self) -> Expression {
        Expression::address_of(Expression::identifier(self.local.clone()))
    }

    /// Returns the Java-visible success return behavior.
    pub fn value(&self) -> &NativeReturn {
        &self.value
    }

    fn jni_type(&self) -> TypeFragment {
        self.value.jni_type()
    }

    fn return_expression(&self) -> Result<Expression> {
        self.value
            .return_expression(Expression::identifier(self.local.clone()))
    }

    fn record(&self) -> Option<&RecordValue> {
        self.value.record()
    }

    fn is_bytes(&self) -> bool {
        self.value.is_bytes()
    }

    fn is_boolean(&self) -> bool {
        self.value.is_boolean()
    }

    fn is_callback(&self) -> bool {
        self.value.is_callback()
    }

    fn from_parameter(parameter: &c::Parameter) -> Result<Self> {
        let c::Type::MutPointer(ty) = parameter.ty() else {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "success out-pointer is not a mutable pointer",
            });
        };
        Ok(Self {
            local: Identifier::parse("__boltffi_return")?,
            c_type: TypeFragment::anonymous(ty)?,
            value: Box::new(NativeReturn::from_c_type(ty)?),
        })
    }
}
