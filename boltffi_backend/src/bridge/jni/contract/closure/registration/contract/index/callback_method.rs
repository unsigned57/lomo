//! Closure registrations discovered inside callback methods.
//!
//! Callback vtable slots can mention closures in both directions: Rust can pass
//! a closure into the JVM callback, and the JVM callback can return a closure to
//! Rust. Both forms must resolve to the same shared closure registration table.
//!
//! This module scans callback slots for those closure shapes and feeds the
//! registration index. It does not build template data; it only ensures the
//! required closure signatures are registered once.

use crate::{
    bridge::{c, jni::JvmClassPath},
    core::Result,
};

use super::ClosureRegistrationIndex;

impl ClosureRegistrationIndex {
    pub fn collect_callback_method(
        self,
        class: &JvmClassPath,
        method: &c::CallbackSlot,
        returned_callback: bool,
        callbacks: &[c::Callback],
    ) -> Result<Self> {
        method.parameter_groups().iter().try_fold(
            self,
            |mut index, group| -> Result<ClosureRegistrationIndex> {
                index.insert_callback_group(class, method, group, returned_callback, callbacks)?;
                Ok(index)
            },
        )
    }

    fn insert_callback_group(
        &mut self,
        class: &JvmClassPath,
        method: &c::CallbackSlot,
        group: &c::ParameterGroup,
        returned_callback: bool,
        callbacks: &[c::Callback],
    ) -> Result<()> {
        match group {
            c::ParameterGroup::Closure(closure) => {
                self.insert_closure_parameter(
                    class,
                    method.parameter(closure.call()).ty(),
                    closure,
                    true,
                    callbacks,
                )?;
            }
            c::ParameterGroup::ClosureReturn(returned) => {
                self.insert_closure_return(class, returned, returned_callback, callbacks)?;
            }
            _ => {}
        }
        Ok(())
    }
}
