//! Direct vectors passed into JVM callback methods.
//!
//! Rust supplies a direct vector to a callback as native pointer plus element
//! count. Java receives that data as a primitive array with a fixed JNI element
//! type. The generated C code must allocate the array, copy the native elements,
//! and pass the Java object to the callback method.
//!
//! This contract stores the names and element type for that operation. It is
//! built from the C callback slot group, so templates do not need to rediscover
//! which pair of parameters forms the vector.

use crate::bridge::{
    c::{Identifier, TypeFragment},
    jni::JniType,
};

/// Direct-vector array argument passed from Rust into a JVM callback method.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackDirectVectorArgument<'argument> {
    array: &'argument Identifier,
    pointer: &'argument Identifier,
    length: &'argument Identifier,
    jni_type: JniType,
}

impl<'argument> CallbackDirectVectorArgument<'argument> {
    pub(in crate::bridge::jni::contract::callback) fn new(
        array: &'argument Identifier,
        pointer: &'argument Identifier,
        length: &'argument Identifier,
        jni_type: JniType,
    ) -> Self {
        Self {
            array,
            pointer,
            length,
            jni_type,
        }
    }

    /// Returns the local JNI array variable.
    pub fn array(&self) -> &Identifier {
        self.array
    }

    /// Returns the C vector pointer parameter.
    pub fn pointer(&self) -> &Identifier {
        self.pointer
    }

    /// Returns the C vector length parameter.
    pub fn length(&self) -> &Identifier {
        self.length
    }

    /// Returns the JNI array type.
    pub fn array_type(&self) -> TypeFragment {
        self.jni_type.as_array_type_fragment()
    }

    /// Returns the JNI array element type.
    pub fn element_type(&self) -> TypeFragment {
        self.jni_type.as_type_fragment()
    }

    /// Returns the `New*Array` JNI function table member.
    pub fn new_array(&self) -> &'static str {
        self.jni_type.new_array()
    }

    /// Returns the `Set*ArrayRegion` JNI function table member.
    pub fn set_region(&self) -> &'static str {
        self.jni_type.set_array_region()
    }
}
