//! Direct-vector arguments for closure calls crossing the JNI bridge.
//!
//! Closure signatures are shared in both directions. When Rust calls a
//! JVM-owned closure, the C trampoline receives pointer plus length and builds a
//! Java primitive array. When Java calls a Rust-owned closure handle, the native
//! method receives a Java primitive array and forwards pointer plus length to the
//! C closure call.
//!
//! This module keeps both halves tied to one argument contract. The JNI element
//! type, C pointer cast, Java array type, and small-array stack-copy policy are
//! selected once from the C direct-vector group and then reused by both template
//! paths.

use crate::{
    bridge::{
        c::{self, DirectVectorElementAbi, Expression, Identifier, TypeFragment},
        jni::{ClosureCParameter, DirectVectorStackCopy, JniType},
    },
    core::Result,
};

/// A closure direct-vector argument with both Java-array and C pointer shapes.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ClosureDirectVectorArgument {
    name: Identifier,
    pointer: ClosureCParameter,
    length: ClosureCParameter,
    pointer_local: Identifier,
    length_local: Identifier,
    pointer_type: TypeFragment,
    jni_type: JniType,
    stack_copy: Option<DirectVectorStackCopy>,
}

impl ClosureDirectVectorArgument {
    pub(in crate::bridge::jni::contract::closure) fn from_vector(
        pointer: &c::Parameter,
        length: &c::Parameter,
        vector: &c::DirectVectorParameter,
    ) -> Result<Self> {
        let jni_type = JniType::from_direct_vector_element(vector.element())?;
        Ok(Self {
            pointer: ClosureCParameter::from_parameter(pointer)?,
            length: ClosureCParameter::from_parameter(length)?,
            pointer_local: Identifier::parse(format!("{}_ptr", vector.name()))?,
            length_local: Identifier::parse(format!("{}_len", vector.name()))?,
            pointer_type: TypeFragment::anonymous(pointer.ty())?,
            stack_copy: matches!(vector.element(), DirectVectorElementAbi::Typed(_))
                .then(|| DirectVectorStackCopy::for_primitive(jni_type)),
            jni_type,
            name: Identifier::escape(vector.name())?,
        })
    }

    /// Returns the Java array argument name.
    pub fn name(&self) -> &Identifier {
        &self.name
    }

    /// Returns the C vector pointer parameter name.
    pub fn pointer(&self) -> &Identifier {
        self.pointer.name()
    }

    /// Returns the C vector length parameter name.
    pub fn length(&self) -> &Identifier {
        self.length.name()
    }

    /// Returns the local Java array element pointer.
    pub fn pointer_local(&self) -> &Identifier {
        &self.pointer_local
    }

    /// Returns the local Java array length.
    pub fn length_local(&self) -> &Identifier {
        &self.length_local
    }

    /// Returns the C pointer type expected by the closure call.
    pub fn pointer_type(&self) -> &TypeFragment {
        &self.pointer_type
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

    /// Returns the `Get*ArrayElements` JNI function table member.
    pub fn getter(&self) -> &'static str {
        self.jni_type.array_elements_getter()
    }

    /// Returns the `Release*ArrayElements` JNI function table member.
    pub fn releaser(&self) -> &'static str {
        self.jni_type.array_elements_releaser()
    }

    /// Returns the stack-copy path for small primitive arrays.
    pub fn stack_copy(&self) -> Option<&DirectVectorStackCopy> {
        self.stack_copy.as_ref()
    }

    /// Returns the C parameters accepted by the closure call trampoline.
    pub fn c_parameters(&self) -> Vec<ClosureCParameter> {
        vec![self.pointer.clone(), self.length.clone()]
    }

    /// Returns the C parameters accepted by the Rust-owned closure handle entrypoint.
    pub fn handle_parameters(&self) -> Vec<ClosureCParameter> {
        vec![ClosureCParameter::new(self.name.clone(), self.array_type())]
    }

    /// Returns the expressions passed to the static JVM closure method.
    pub fn jvm_arguments(&self) -> Vec<Expression> {
        vec![Expression::identifier(self.name.clone())]
    }

    /// Returns the expressions passed into the Rust closure call function.
    pub fn rust_arguments(&self) -> Vec<Expression> {
        vec![
            Expression::cast(
                self.pointer_type.clone(),
                Expression::identifier(self.pointer_local.clone()),
            ),
            Expression::cast(
                TypeFragment::new("uintptr_t"),
                Expression::identifier(self.length_local.clone()),
            ),
        ]
    }

    /// Returns the JNI method descriptor segment for this argument.
    pub fn jni_signature(&self) -> &'static str {
        self.jni_type.array_signature()
    }
}
