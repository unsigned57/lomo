//! Source fields for closure direct-vector arguments.
//!
//! The closure contract describes one direct-vector argument in both directions.
//! The C templates need source-shaped fields for each side: array allocation and
//! `Set*ArrayRegion` when Rust calls Java, borrowed-array locals and cleanup
//! when Java calls a Rust-owned closure handle.
//!
//! This module prepares those fields from the contract. The templates receive
//! names and JNI function-table members, not the raw direct-vector group.

use crate::{
    bridge::{
        c::{Identifier, TypeFragment},
        jni::ClosureDirectVectorArgument,
    },
    core::Result,
};

/// Template input for one closure direct-vector argument.
pub struct ClosureDirectVectorArgumentView {
    pub name: Identifier,
    pub pointer: Identifier,
    pub length: Identifier,
    pub pointer_local: Identifier,
    pub length_local: Identifier,
    pub array_type: TypeFragment,
    pub element_type: TypeFragment,
    pub new_array: &'static str,
    pub set_region: &'static str,
    pub getter: &'static str,
    pub releaser: &'static str,
    pub stack_copy: Option<ClosureDirectVectorStackCopyView>,
}

/// Stack-copy template fields for a Rust-owned closure handle call.
///
/// Small Java primitive arrays use `Get*ArrayRegion` into local storage. Larger
/// arrays use the normal borrowed-elements path and set `needs_release`.
pub struct ClosureDirectVectorStackCopyView {
    pub storage: Identifier,
    pub needs_release: Identifier,
    pub max_len: usize,
    pub region_getter: &'static str,
}

impl ClosureDirectVectorArgumentView {
    pub fn from_argument(argument: &ClosureDirectVectorArgument) -> Result<Self> {
        Ok(Self {
            name: argument.name().clone(),
            pointer: argument.pointer().clone(),
            length: argument.length().clone(),
            pointer_local: argument.pointer_local().clone(),
            length_local: argument.length_local().clone(),
            array_type: argument.array_type(),
            element_type: argument.element_type(),
            new_array: argument.new_array(),
            set_region: argument.set_region(),
            getter: argument.getter(),
            releaser: argument.releaser(),
            stack_copy: argument
                .stack_copy()
                .map(|stack_copy| -> Result<ClosureDirectVectorStackCopyView> {
                    Ok(ClosureDirectVectorStackCopyView {
                        storage: Identifier::parse(format!("__boltffi_{}_stack", argument.name()))?,
                        needs_release: Identifier::parse(format!(
                            "__boltffi_{}_needs_release",
                            argument.name()
                        ))?,
                        max_len: stack_copy.max_len(),
                        region_getter: stack_copy.region_getter(),
                    })
                })
                .transpose()?,
        })
    }
}
