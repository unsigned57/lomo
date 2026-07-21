//! Source fields for closure handles that originate inside callbacks.
//!
//! JVM callback methods can receive closures from Rust. Java sees those
//! closures as handle tokens, while Rust expects a C function pointer plus
//! context and release pointer. The generated bridge class therefore needs
//! native methods that call and release the closure behind the token.
//!
//! This module prepares that handle wrapper view from the registered closure
//! contract. It shares the same closure rendering path used by normal closure
//! parameters instead of creating a callback-only closure model.

use crate::{
    bridge::{
        c::{Expression, Identifier, Literal, Statement, TypeFragment},
        jni::ClosureRegistration,
    },
    core::Result,
};

use super::ClosureRegistrationView;

pub struct CallbackClosureHandleView {
    pub ty: Identifier,
    pub new: Identifier,
    pub ref_: Identifier,
    pub release: Identifier,
    pub call_symbol: Identifier,
    pub release_symbol: Identifier,
    pub call_field: Statement,
    pub jni_return_type: TypeFragment,
    pub failure_value: Expression,
    pub closure: ClosureRegistrationView,
}

impl CallbackClosureHandleView {
    pub fn from_registration(registration: &ClosureRegistration) -> Result<Option<Self>> {
        registration
            .callback_handle()
            .map(|handle| {
                Ok(Self {
                    ty: handle.ty().clone(),
                    new: handle.new_function().clone(),
                    ref_: handle.ref_function().clone(),
                    release: handle.release_function().clone(),
                    call_symbol: handle.call_symbol().as_identifier().clone(),
                    release_symbol: handle.release_symbol().as_identifier().clone(),
                    call_field: handle.call_field().clone(),
                    jni_return_type: registration.callback_return_type(),
                    failure_value: registration
                        .callback_failure_value()
                        .unwrap_or_else(|| Expression::literal(Literal::integer_zero())),
                    closure: ClosureRegistrationView::from_registration(registration)?,
                })
            })
            .transpose()
    }
}
