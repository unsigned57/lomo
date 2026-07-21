//! Closure registrations discovered inside native methods.
//!
//! Exported Rust functions and methods can accept JVM-owned closures or return
//! Rust-owned closures. The lower C function groups contain those closure
//! signatures, and the JNI bridge needs each signature registered before native
//! method rendering can use it.
//!
//! This module scans C functions for closure parameter and return groups and
//! feeds the shared registration index.

use crate::{
    bridge::{c, jni::JvmClassPath},
    core::Result,
};

use super::ClosureRegistrationIndex;

impl ClosureRegistrationIndex {
    pub fn collect_function(
        self,
        class: &JvmClassPath,
        function: &c::Function,
        callbacks: &[c::Callback],
    ) -> Result<Self> {
        function.parameter_groups().iter().try_fold(
            self,
            |mut index, group| -> Result<ClosureRegistrationIndex> {
                index.insert_function_group(class, function, group, callbacks)?;
                Ok(index)
            },
        )
    }

    fn insert_function_group(
        &mut self,
        class: &JvmClassPath,
        function: &c::Function,
        group: &c::ParameterGroup,
        callbacks: &[c::Callback],
    ) -> Result<()> {
        if let c::ParameterGroup::Closure(closure) = group {
            self.insert_closure_parameter(
                class,
                function.parameter(closure.call()).ty(),
                closure,
                false,
                callbacks,
            )?;
        }
        Ok(())
    }
}
