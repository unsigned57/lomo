use crate::{
    bridge::{
        c::{self, Identifier, TypeFragment},
        jni::{JniSymbolName, JniType, JvmClassPath},
    },
    core::{Error, Result},
};

const JNI_BRIDGE: &str = "jni";

/// JNI helper that writes one fallible success value.
///
/// Fallible callback and closure calls return their error payload through the
/// JVM method return slot. When the call succeeds with a value, generated JVM
/// target code calls this helper to write that value into the C success
/// out-pointer.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct SuccessOutWriter {
    method: Identifier,
    symbol: JniSymbolName,
    value: SuccessOutValue,
}

/// Success value shape accepted by a success writer.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum SuccessOutValue {
    /// Scalar value written directly into the C out-pointer.
    Scalar {
        /// C storage type behind the success pointer.
        c_type: TypeFragment,
        /// JNI value type accepted by the helper.
        jni_type: JniType,
    },
    /// Owned encoded bytes written as `FfiBuf_u8`.
    Bytes,
    /// Direct record copied from a JVM byte array into C storage.
    Record {
        /// C record type behind the success pointer.
        c_type: TypeFragment,
    },
}

impl SuccessOutWriter {
    /// Builds all success writers needed by callback vtables and closure calls.
    pub fn from_c_bridge(class: &JvmClassPath, c_bridge: &c::CBridgeContract) -> Result<Vec<Self>> {
        let mut types = Vec::new();
        c_bridge
            .callbacks()
            .iter()
            .flat_map(c::Callback::methods)
            .try_for_each(|method| {
                Self::collect_success_types(&mut types, SuccessOutSource::Callback(method), true)
            })?;
        c_bridge.functions().iter().try_for_each(|function| {
            Self::collect_success_types(&mut types, SuccessOutSource::Function(function), false)
        })?;
        types
            .into_iter()
            .map(|ty| Self::from_type(class, ty))
            .collect()
    }

    /// Returns the native method declared in generated Kotlin.
    pub fn method(&self) -> &Identifier {
        &self.method
    }

    /// Returns the JNI symbol implemented in generated C.
    pub fn symbol(&self) -> &JniSymbolName {
        &self.symbol
    }

    /// Returns the value shape accepted by this writer.
    pub fn value(&self) -> &SuccessOutValue {
        &self.value
    }

    fn from_parameter(parameter: &c::Parameter) -> Result<c::Type> {
        let c::Type::MutPointer(inner) = parameter.ty() else {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "success out parameter is not a mutable pointer",
            });
        };
        Ok(inner.as_ref().clone())
    }

    fn from_type(class: &JvmClassPath, ty: c::Type) -> Result<Self> {
        let method = Self::method_for_type(&ty)?;
        Ok(Self {
            symbol: JniSymbolName::native_method(class, method.as_str())?,
            method,
            value: SuccessOutValue::from_type(&ty)?,
        })
    }

    /// Returns the helper method needed for one C success out-parameter.
    pub fn method_for_parameter(parameter: &c::Parameter) -> Result<Identifier> {
        Self::from_parameter(parameter).and_then(|ty| Self::method_for_type(&ty))
    }

    fn method_for_type(ty: &c::Type) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_success_{}", Self::suffix(ty)?))
    }

    fn suffix(ty: &c::Type) -> Result<String> {
        Ok(match ty {
            c::Type::Bool => "bool".to_owned(),
            c::Type::Int8 => "i8".to_owned(),
            c::Type::Uint8 => "u8".to_owned(),
            c::Type::Int16 => "i16".to_owned(),
            c::Type::Uint16 => "u16".to_owned(),
            c::Type::Int32 => "i32".to_owned(),
            c::Type::Uint32 => "u32".to_owned(),
            c::Type::Int64 => "i64".to_owned(),
            c::Type::Uint64 => "u64".to_owned(),
            c::Type::SignedPointerWidth => "isize".to_owned(),
            c::Type::PointerWidth => "usize".to_owned(),
            c::Type::Float32 => "f32".to_owned(),
            c::Type::Float64 => "f64".to_owned(),
            c::Type::Buffer => "bytes".to_owned(),
            c::Type::CStyleEnum { name, .. } => format!("enum_{name}"),
            c::Type::DirectRecord(name) => format!("record_{name}"),
            _ => {
                return Err(Error::UnsupportedBridge {
                    bridge: JNI_BRIDGE,
                    shape: "success out value",
                });
            }
        })
    }

    fn collect_success_types(
        types: &mut Vec<c::Type>,
        source: SuccessOutSource<'_>,
        include_source_success: bool,
    ) -> Result<()> {
        source
            .parameter_groups()
            .iter()
            .try_for_each(|group| match group {
                c::ParameterGroup::SuccessOut(index) if include_source_success => {
                    let ty = Self::from_parameter(source.parameter(*index))?;
                    if !types.contains(&ty) {
                        types.push(ty);
                    }
                    Ok(())
                }
                c::ParameterGroup::Closure(closure) => {
                    Self::collect_success_types(types, SuccessOutSource::Closure(closure), true)
                }
                c::ParameterGroup::ClosureReturn(returned) => Self::collect_success_types(
                    types,
                    SuccessOutSource::ClosureReturn(returned),
                    true,
                ),
                _ => Ok(()),
            })
    }
}

#[derive(Clone, Copy)]
enum SuccessOutSource<'source> {
    Function(&'source c::Function),
    Callback(&'source c::CallbackSlot),
    Closure(&'source c::ClosureParameter),
    ClosureReturn(&'source c::ClosureReturnParameter),
}

impl<'source> SuccessOutSource<'source> {
    fn parameter_groups(self) -> &'source [c::ParameterGroup] {
        match self {
            Self::Function(function) => function.parameter_groups(),
            Self::Callback(callback) => callback.parameter_groups(),
            Self::Closure(closure) => closure.parameter_groups(),
            Self::ClosureReturn(returned) => returned.parameter_groups(),
        }
    }

    fn parameter(self, index: c::ParameterIndex) -> &'source c::Parameter {
        match self {
            Self::Function(function) => function.parameter(index),
            Self::Callback(callback) => callback.parameter(index),
            Self::Closure(closure) => closure.parameter(index),
            Self::ClosureReturn(returned) => returned.parameter(index),
        }
    }
}

/// Hidden JVM argument that points at fallible success storage.
///
/// The JVM method returns the encoded error payload. A non-error success value
/// is written through this pointer by a generated helper method.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct SuccessOutArgument {
    name: Identifier,
    jni_type: JniType,
    writer: Identifier,
}

impl SuccessOutArgument {
    /// Creates a success out argument from its C out-pointer parameter.
    pub fn from_parameter(parameter: &c::Parameter) -> Result<Self> {
        Ok(Self {
            name: Identifier::parse(parameter.name())?,
            jni_type: JniType::from_c_type(parameter.ty())?,
            writer: SuccessOutWriter::method_for_parameter(parameter)?,
        })
    }

    /// Returns the JVM parameter name.
    pub fn name(&self) -> &Identifier {
        &self.name
    }

    /// Returns the JNI type used to carry the success pointer.
    pub fn jni_type(&self) -> JniType {
        self.jni_type
    }

    /// Returns the generated native helper that writes the success value.
    pub fn writer(&self) -> &Identifier {
        &self.writer
    }
}

impl SuccessOutValue {
    fn from_type(ty: &c::Type) -> Result<Self> {
        Ok(match ty {
            c::Type::Buffer => Self::Bytes,
            c::Type::DirectRecord(_) => Self::Record {
                c_type: TypeFragment::anonymous(ty)?,
            },
            c::Type::CStyleEnum { repr, .. } => Self::Scalar {
                c_type: TypeFragment::anonymous(ty)?,
                jni_type: JniType::from_c_type(repr)?,
            },
            _ => Self::Scalar {
                c_type: TypeFragment::anonymous(ty)?,
                jni_type: JniType::from_c_type(ty)?,
            },
        })
    }

    /// Returns the C storage type when the writer stores a typed C value.
    pub fn c_type(&self) -> Option<&TypeFragment> {
        match self {
            Self::Scalar { c_type, .. } | Self::Record { c_type } => Some(c_type),
            Self::Bytes => None,
        }
    }

    /// Returns the JNI scalar type accepted by scalar writers.
    pub fn jni_type(&self) -> Option<JniType> {
        match self {
            Self::Scalar { jni_type, .. } => Some(*jni_type),
            Self::Bytes | Self::Record { .. } => None,
        }
    }

    /// Returns whether the writer receives a JVM byte array.
    pub fn uses_byte_array(&self) -> bool {
        matches!(self, Self::Bytes | Self::Record { .. })
    }

    /// Returns whether the writer copies a direct record from bytes.
    pub fn uses_record_copy(&self) -> bool {
        matches!(self, Self::Record { .. })
    }
}
