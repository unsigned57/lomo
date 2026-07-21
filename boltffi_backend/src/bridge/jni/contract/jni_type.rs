//! JNI primitive type vocabulary.
//!
//! One primitive value has several spellings in generated JNI glue: the native
//! C alias such as `jint`, the array type such as `jintArray`, the JVM method
//! descriptor such as `I`, and the function-table members used to borrow,
//! release, allocate, or copy arrays.
//!
//! This module keeps those spellings behind one enum. Contract builders ask the
//! enum for the spelling they need, which keeps templates from carrying their
//! own primitive tables and prevents one call path from drifting away from
//! another.

use crate::{
    bridge::c::{self, DirectVectorElementAbi, Literal, Type, TypeFragment},
    core::{Error, Result},
};

const JNI_BRIDGE: &str = "jni";

/// A JNI primitive type and its related source, descriptor, and array spellings.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub enum JniType {
    /// `jboolean`.
    Boolean,
    /// `jbyte`.
    Byte,
    /// `jshort`.
    Short,
    /// `jint`.
    Int,
    /// `jlong`.
    Long,
    /// `jfloat`.
    Float,
    /// `jdouble`.
    Double,
}

impl JniType {
    /// Returns the JNI type as C syntax.
    pub fn as_type_fragment(self) -> TypeFragment {
        TypeFragment::new(match self {
            Self::Boolean => "jboolean",
            Self::Byte => "jbyte",
            Self::Short => "jshort",
            Self::Int => "jint",
            Self::Long => "jlong",
            Self::Float => "jfloat",
            Self::Double => "jdouble",
        })
    }

    /// Returns the JNI array type as C syntax.
    pub fn as_array_type_fragment(self) -> TypeFragment {
        TypeFragment::new(match self {
            Self::Boolean => "jbooleanArray",
            Self::Byte => "jbyteArray",
            Self::Short => "jshortArray",
            Self::Int => "jintArray",
            Self::Long => "jlongArray",
            Self::Float => "jfloatArray",
            Self::Double => "jdoubleArray",
        })
    }

    /// Returns the `Get*ArrayElements` JNI function table member.
    pub fn array_elements_getter(self) -> &'static str {
        match self {
            Self::Boolean => "GetBooleanArrayElements",
            Self::Byte => "GetByteArrayElements",
            Self::Short => "GetShortArrayElements",
            Self::Int => "GetIntArrayElements",
            Self::Long => "GetLongArrayElements",
            Self::Float => "GetFloatArrayElements",
            Self::Double => "GetDoubleArrayElements",
        }
    }

    /// Returns the `Get*ArrayRegion` JNI function table member.
    pub fn array_region_getter(self) -> &'static str {
        match self {
            Self::Boolean => "GetBooleanArrayRegion",
            Self::Byte => "GetByteArrayRegion",
            Self::Short => "GetShortArrayRegion",
            Self::Int => "GetIntArrayRegion",
            Self::Long => "GetLongArrayRegion",
            Self::Float => "GetFloatArrayRegion",
            Self::Double => "GetDoubleArrayRegion",
        }
    }

    /// Returns the `Release*ArrayElements` JNI function table member.
    pub fn array_elements_releaser(self) -> &'static str {
        match self {
            Self::Boolean => "ReleaseBooleanArrayElements",
            Self::Byte => "ReleaseByteArrayElements",
            Self::Short => "ReleaseShortArrayElements",
            Self::Int => "ReleaseIntArrayElements",
            Self::Long => "ReleaseLongArrayElements",
            Self::Float => "ReleaseFloatArrayElements",
            Self::Double => "ReleaseDoubleArrayElements",
        }
    }

    /// Returns whether this type is `jboolean`.
    pub fn is_boolean(self) -> bool {
        matches!(self, Self::Boolean)
    }

    /// Returns the JNI type descriptor used in method signatures.
    pub fn signature(self) -> &'static str {
        match self {
            Self::Boolean => "Z",
            Self::Byte => "B",
            Self::Short => "S",
            Self::Int => "I",
            Self::Long => "J",
            Self::Float => "F",
            Self::Double => "D",
        }
    }

    /// Returns the JNI array descriptor for this scalar type.
    pub fn array_signature(self) -> &'static str {
        match self {
            Self::Boolean => "[Z",
            Self::Byte => "[B",
            Self::Short => "[S",
            Self::Int => "[I",
            Self::Long => "[J",
            Self::Float => "[F",
            Self::Double => "[D",
        }
    }

    /// Returns the `New*Array` JNI function table member.
    pub fn new_array(self) -> &'static str {
        match self {
            Self::Boolean => "NewBooleanArray",
            Self::Byte => "NewByteArray",
            Self::Short => "NewShortArray",
            Self::Int => "NewIntArray",
            Self::Long => "NewLongArray",
            Self::Float => "NewFloatArray",
            Self::Double => "NewDoubleArray",
        }
    }

    /// Returns the `Set*ArrayRegion` JNI function table member.
    pub fn set_array_region(self) -> &'static str {
        match self {
            Self::Boolean => "SetBooleanArrayRegion",
            Self::Byte => "SetByteArrayRegion",
            Self::Short => "SetShortArrayRegion",
            Self::Int => "SetIntArrayRegion",
            Self::Long => "SetLongArrayRegion",
            Self::Float => "SetFloatArrayRegion",
            Self::Double => "SetDoubleArrayRegion",
        }
    }

    /// Returns the `CallStatic*Method` suffix for this JNI scalar type.
    pub fn call_method_suffix(self) -> &'static str {
        match self {
            Self::Boolean => "Boolean",
            Self::Byte => "Byte",
            Self::Short => "Short",
            Self::Int => "Int",
            Self::Long => "Long",
            Self::Float => "Float",
            Self::Double => "Double",
        }
    }

    /// Returns the C expression used when callback dispatch fails.
    pub fn failure_value(self) -> Literal {
        match self {
            Self::Boolean => Literal::bool_false(),
            Self::Byte | Self::Short | Self::Int | Self::Long => Literal::integer_zero(),
            Self::Float => Literal::f32_zero(),
            Self::Double => Literal::f64_zero(),
        }
    }

    /// Creates the JNI scalar type for a scalar C ABI type.
    pub fn from_c_type(ty: &c::Type) -> Result<Self> {
        match ty {
            c::Type::Bool => Ok(Self::Boolean),
            c::Type::CStyleEnum { repr, .. } => Self::from_c_type(repr),
            c::Type::Int8 | c::Type::Uint8 | c::Type::StreamPollResult => Ok(Self::Byte),
            c::Type::Int16 | c::Type::Uint16 => Ok(Self::Short),
            c::Type::Int32 | c::Type::Uint32 | c::Type::WaitResult => Ok(Self::Int),
            c::Type::Int64
            | c::Type::Uint64
            | c::Type::SignedPointerWidth
            | c::Type::PointerWidth
            | c::Type::FutureHandle
            | c::Type::ConstPointer(_)
            | c::Type::MutPointer(_)
            | c::Type::FunctionPointer { .. } => Ok(Self::Long),
            c::Type::Float32 => Ok(Self::Float),
            c::Type::Float64 => Ok(Self::Double),
            c::Type::CallbackHandle(_) => Err(Error::UnsupportedBridge {
                bridge: JNI_BRIDGE,
                shape: "callback handle C ABI",
            }),
            c::Type::Void
            | c::Type::Status
            | c::Type::Buffer
            | c::Type::String
            | c::Type::Span
            | c::Type::Named(_)
            | c::Type::DirectRecord(_) => Err(Error::UnsupportedBridge {
                bridge: JNI_BRIDGE,
                shape: "non-scalar C ABI function",
            }),
        }
    }

    /// Creates the JNI scalar type used for a direct-vector element.
    pub fn from_direct_vector_element(element: &DirectVectorElementAbi) -> Result<Self> {
        match element {
            DirectVectorElementAbi::Typed(element) => Self::from_c_type(element),
            DirectVectorElementAbi::PackedBytes => Self::from_c_type(&Type::Uint8),
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::bridge::c::Identifier;

    use super::*;

    #[test]
    fn scalar_c_types_map_to_jni_primitives() {
        [
            (Type::Bool, JniType::Boolean),
            (Type::Int8, JniType::Byte),
            (Type::Uint8, JniType::Byte),
            (Type::Int16, JniType::Short),
            (Type::Uint16, JniType::Short),
            (Type::Int32, JniType::Int),
            (Type::Uint32, JniType::Int),
            (Type::Int64, JniType::Long),
            (Type::Uint64, JniType::Long),
            (Type::SignedPointerWidth, JniType::Long),
            (Type::PointerWidth, JniType::Long),
            (Type::FutureHandle, JniType::Long),
            (Type::Float32, JniType::Float),
            (Type::Float64, JniType::Double),
            (Type::StreamPollResult, JniType::Byte),
            (Type::WaitResult, JniType::Int),
        ]
        .into_iter()
        .for_each(|(c_type, jni_type)| {
            assert_eq!(
                JniType::from_c_type(&c_type).expect("scalar JNI type"),
                jni_type
            );
        });
    }

    #[test]
    fn c_style_enums_use_their_repr_jni_type() {
        let enumeration = Type::CStyleEnum {
            name: Identifier::parse("Mode").expect("C enum name"),
            repr: Box::new(Type::Uint8),
        };

        assert_eq!(
            JniType::from_c_type(&enumeration).expect("enum JNI type"),
            JniType::Byte
        );
    }

    #[test]
    fn jni_type_owns_related_scalar_spellings() {
        [
            (
                JniType::Boolean,
                "jboolean",
                "jbooleanArray",
                "Z",
                "[Z",
                "GetBooleanArrayElements",
                "GetBooleanArrayRegion",
                "ReleaseBooleanArrayElements",
                "NewBooleanArray",
                "SetBooleanArrayRegion",
                "Boolean",
            ),
            (
                JniType::Byte,
                "jbyte",
                "jbyteArray",
                "B",
                "[B",
                "GetByteArrayElements",
                "GetByteArrayRegion",
                "ReleaseByteArrayElements",
                "NewByteArray",
                "SetByteArrayRegion",
                "Byte",
            ),
            (
                JniType::Short,
                "jshort",
                "jshortArray",
                "S",
                "[S",
                "GetShortArrayElements",
                "GetShortArrayRegion",
                "ReleaseShortArrayElements",
                "NewShortArray",
                "SetShortArrayRegion",
                "Short",
            ),
            (
                JniType::Int,
                "jint",
                "jintArray",
                "I",
                "[I",
                "GetIntArrayElements",
                "GetIntArrayRegion",
                "ReleaseIntArrayElements",
                "NewIntArray",
                "SetIntArrayRegion",
                "Int",
            ),
            (
                JniType::Long,
                "jlong",
                "jlongArray",
                "J",
                "[J",
                "GetLongArrayElements",
                "GetLongArrayRegion",
                "ReleaseLongArrayElements",
                "NewLongArray",
                "SetLongArrayRegion",
                "Long",
            ),
            (
                JniType::Float,
                "jfloat",
                "jfloatArray",
                "F",
                "[F",
                "GetFloatArrayElements",
                "GetFloatArrayRegion",
                "ReleaseFloatArrayElements",
                "NewFloatArray",
                "SetFloatArrayRegion",
                "Float",
            ),
            (
                JniType::Double,
                "jdouble",
                "jdoubleArray",
                "D",
                "[D",
                "GetDoubleArrayElements",
                "GetDoubleArrayRegion",
                "ReleaseDoubleArrayElements",
                "NewDoubleArray",
                "SetDoubleArrayRegion",
                "Double",
            ),
        ]
        .into_iter()
        .for_each(
            |(
                jni_type,
                scalar,
                array,
                signature,
                array_signature,
                getter,
                region_getter,
                releaser,
                new_array,
                set_array_region,
                call_suffix,
            )| {
                assert_eq!(jni_type.as_type_fragment().to_string(), scalar);
                assert_eq!(jni_type.as_array_type_fragment().to_string(), array);
                assert_eq!(jni_type.signature(), signature);
                assert_eq!(jni_type.array_signature(), array_signature);
                assert_eq!(jni_type.array_elements_getter(), getter);
                assert_eq!(jni_type.array_region_getter(), region_getter);
                assert_eq!(jni_type.array_elements_releaser(), releaser);
                assert_eq!(jni_type.new_array(), new_array);
                assert_eq!(jni_type.set_array_region(), set_array_region);
                assert_eq!(jni_type.call_method_suffix(), call_suffix);
            },
        );
    }
}
