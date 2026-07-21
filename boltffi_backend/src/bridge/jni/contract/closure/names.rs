//! Generated names for closure bridge classes and symbols.
//!
//! One closure signature needs several related names: a JVM bridge class, native
//! call and release symbols, and helper symbols for closure handles. If those
//! names are derived in multiple places, signatures that should share one
//! registration can drift apart.
//!
//! This module derives the closure naming set from the signature and owner class
//! once. Registration construction then carries those names as contract data
//! instead of letting templates invent them.

use boltffi_binding::ClosureSignature;

use crate::{
    bridge::{
        c::Identifier,
        jni::{JniSymbolName, JvmClassPath},
    },
    core::Result,
};

pub struct ClosureNames {
    stem: String,
}

impl ClosureNames {
    pub fn new(signature: &ClosureSignature) -> Self {
        Self {
            stem: signature.symbol_part(),
        }
    }

    pub fn global_class(&self) -> Result<Identifier> {
        Identifier::parse(format!("g_{}_class", self.stem))
    }

    pub fn call_method(&self) -> Result<Identifier> {
        Identifier::parse(format!("g_{}_call_method", self.stem))
    }

    pub fn free_method(&self) -> Result<Identifier> {
        Identifier::parse(format!("g_{}_free_method", self.stem))
    }

    pub fn load(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_load_{}", self.stem))
    }

    pub fn unload(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_unload_{}", self.stem))
    }

    pub fn call(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_{}_call", self.stem))
    }

    pub fn release(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_{}_release", self.stem))
    }

    pub fn handle_type(&self) -> Result<Identifier> {
        Identifier::parse(format!("BoltFFIJniClosure{}", self.stem))
    }

    pub fn handle_new(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_{}_handle_new", self.stem))
    }

    pub fn handle_ref(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_{}_handle_ref", self.stem))
    }

    pub fn handle_release(&self) -> Result<Identifier> {
        Identifier::parse(format!("boltffi_jni_{}_handle_release", self.stem))
    }

    pub fn handle_call_symbol(&self, class: &JvmClassPath) -> Result<JniSymbolName> {
        JniSymbolName::native_method(
            class,
            &format!("boltffi_callback_closure_{}_call", self.stem),
        )
    }

    pub fn handle_release_symbol(&self, class: &JvmClassPath) -> Result<JniSymbolName> {
        JniSymbolName::native_method(
            class,
            &format!("boltffi_callback_closure_{}_release", self.stem),
        )
    }
}
