//! Deduplicated closure registration index.
//!
//! Closure signatures are structural. The same signature can be used by a
//! function parameter, callback argument, returned closure, or nested closure.
//! Generating a separate bridge class for every occurrence would produce
//! duplicate symbols and make callback-carried closures drift from normal
//! closures.
//!
//! This index walks the C bridge shapes that can mention closures and stores one
//! registration per signature. The order is deterministic so generated source is
//! stable.

mod callback_method;
mod function;
mod parameter;
mod return_value;

use std::collections::{BTreeMap, BTreeSet};

use boltffi_binding::{CallbackId, ClosureSignature};

use crate::{
    bridge::{c, jni::JvmClassPath},
    core::Result,
};

use super::ClosureRegistration;

#[derive(Default)]
pub struct ClosureRegistrationIndex {
    registrations: BTreeMap<ClosureSignature, ClosureRegistration>,
}

impl ClosureRegistrationIndex {
    pub fn from_c_bridge(
        class: &JvmClassPath,
        functions: &[c::Function],
        callbacks: &[c::Callback],
        returned_callbacks: &BTreeSet<CallbackId>,
    ) -> Result<Self> {
        functions
            .iter()
            .try_fold(Self::default(), |index, function| {
                index.collect_function(class, function, callbacks)
            })
            .and_then(|index| {
                callbacks.iter().try_fold(index, |index, callback| {
                    let returned_callback = returned_callbacks.contains(&callback.id());
                    callback.methods().iter().try_fold(index, |index, slot| {
                        index.collect_callback_method(class, slot, returned_callback, callbacks)
                    })
                })
            })
    }

    pub fn into_registrations(self) -> Vec<ClosureRegistration> {
        self.registrations.into_values().collect()
    }
}
