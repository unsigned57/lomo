//! Source fields for direct vectors delivered to JVM callbacks.
//!
//! Rust passes direct vectors through C as pointer plus element count. The JVM
//! method receives a Java primitive array instead, so the generated C must
//! allocate the right array type and copy the native elements with the matching
//! `Set*ArrayRegion` function.
//!
//! This module prepares that array allocation view from the callback contract.
//! The element type and copy function are selected once by the contract layer,
//! then printed by the callback template without re-reading the original type.

use crate::bridge::{
    c::{Identifier, TypeFragment},
    jni::CallbackDirectVectorArgument,
};

pub struct CallbackDirectVectorArgumentView {
    pub array: Identifier,
    pub pointer: Identifier,
    pub length: Identifier,
    pub array_type: TypeFragment,
    pub element_type: TypeFragment,
    pub new_array: &'static str,
    pub set_region: &'static str,
}

impl CallbackDirectVectorArgumentView {
    pub fn from_argument(argument: &CallbackDirectVectorArgument<'_>) -> Self {
        Self {
            array: argument.array().clone(),
            pointer: argument.pointer().clone(),
            length: argument.length().clone(),
            array_type: argument.array_type(),
            element_type: argument.element_type(),
            new_array: argument.new_array(),
            set_region: argument.set_region(),
        }
    }
}
