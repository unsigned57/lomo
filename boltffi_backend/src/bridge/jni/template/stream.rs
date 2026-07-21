//! Source fields for stream-specific JNI helpers.
//!
//! Most stream protocol functions render through the normal native-method path.
//! Direct stream batches need extra source fields because the generated method
//! allocates a Java byte array, asks the C bridge to fill a native item buffer,
//! and copies the used bytes back to the JVM.
//!
//! This module prepares that direct-batch view from the stream contract. It
//! keeps the direct-batch allocation and copy names in one place instead of
//! scattering them through the root source template.

use crate::{
    bridge::{
        c::{Identifier, TypeFragment},
        jni::DirectStreamBatchMethod,
    },
    core::Result,
};

pub struct DirectStreamBatchView {
    pub symbol: Identifier,
    pub c_function: Identifier,
    pub subscription_type: TypeFragment,
    pub item_type: TypeFragment,
    pub item_size: u64,
    pub subscription: Identifier,
    pub max_count: Identifier,
    pub capacity: Identifier,
    pub byte_capacity: Identifier,
    pub items: Identifier,
    pub count: Identifier,
    pub byte_len: Identifier,
    pub array: Identifier,
}

impl DirectStreamBatchView {
    pub fn from_method(method: &DirectStreamBatchMethod) -> Result<Self> {
        Ok(Self {
            symbol: method.symbol().as_identifier().clone(),
            c_function: Identifier::parse(method.c_function().name())?,
            subscription_type: method.subscription_type().clone(),
            item_type: method.item_type().clone(),
            item_size: method.item_size(),
            subscription: Identifier::parse("subscription")?,
            max_count: Identifier::parse("max_count")?,
            capacity: Identifier::parse("__boltffi_capacity")?,
            byte_capacity: Identifier::parse("__boltffi_byte_capacity")?,
            items: Identifier::parse("__boltffi_items")?,
            count: Identifier::parse("__boltffi_count")?,
            byte_len: Identifier::parse("__boltffi_byte_len")?,
            array: Identifier::parse("__boltffi_array")?,
        })
    }
}
