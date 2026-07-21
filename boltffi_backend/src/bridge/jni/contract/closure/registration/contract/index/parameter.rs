//! Closure registrations discovered inside C parameter groups.
//!
//! Functions, callback slots, and closure trampolines all use C parameter groups
//! to describe closure arguments. A closure can appear directly or nested inside
//! another closure signature.
//!
//! This module extracts closure signatures from those shared parameter groups
//! and feeds the registration index. Keeping the scan here avoids one closure
//! discovery path per declaration kind.

use crate::{
    bridge::{c, jni::JvmClassPath},
    core::Result,
};

use super::super::build::ClosureRegistrationConstructor;
use super::ClosureRegistrationIndex;

impl ClosureRegistrationIndex {
    pub fn insert_closure_parameter(
        &mut self,
        class: &JvmClassPath,
        call_type: &c::Type,
        closure: &c::ClosureParameter,
        callback_argument: bool,
        callbacks: &[c::Callback],
    ) -> Result<()> {
        let inserted = match self.registrations.get_mut(closure.signature()) {
            Some(registration) => {
                if callback_argument {
                    ClosureRegistrationConstructor::retain_callback_handle(
                        registration,
                        class,
                        call_type,
                    )?;
                }
                false
            }
            None => {
                self.registrations.insert(
                    closure.signature().clone(),
                    ClosureRegistrationConstructor::from_closure_parameter(
                        class,
                        call_type,
                        closure,
                        callback_argument,
                        callbacks,
                    )?,
                );
                true
            }
        };

        if inserted {
            closure
                .parameter_groups()
                .iter()
                .try_for_each(|group| -> Result<()> {
                    if let c::ParameterGroup::Closure(nested) = group {
                        self.insert_closure_parameter(
                            class,
                            closure.parameter(nested.call()).ty(),
                            nested,
                            true,
                            callbacks,
                        )?;
                    }
                    Ok(())
                })?;
        }

        Ok(())
    }
}
