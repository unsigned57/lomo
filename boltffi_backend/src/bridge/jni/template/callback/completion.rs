//! Source fields for async callback completion methods.
//!
//! Async callback methods do not return their payload directly through the JVM
//! method call. The JVM receives a completion handle and later calls generated
//! native methods to report success or failure back to Rust.
//!
//! This module prepares those completion method fields from the contract:
//! success and failure symbols, optional payload types, and callback-handle
//! construction when the payload itself is a returned callback.

use crate::bridge::{
    c::{Identifier, TypeFragment},
    jni::{CallbackCompletionInvoker, CallbackCompletionPayload},
};

pub struct CallbackCompletionInvokerView {
    pub success: Identifier,
    pub failure: Identifier,
    pub error: Option<Identifier>,
    pub has_payload: bool,
    pub payload_c_type: TypeFragment,
    pub payload_jni_type: TypeFragment,
    pub payload_bytes: bool,
    pub payload_record: bool,
    pub payload_callback_handle: bool,
    pub payload_create_handle: Option<Identifier>,
}

impl CallbackCompletionInvokerView {
    pub fn from_invoker(invoker: &CallbackCompletionInvoker) -> Self {
        let payload = invoker.payload();
        Self {
            success: invoker.success().as_identifier().clone(),
            failure: invoker.failure().as_identifier().clone(),
            error: invoker.error().map(|symbol| symbol.as_identifier().clone()),
            has_payload: payload.is_some(),
            payload_c_type: payload
                .map(CallbackCompletionPayload::c_type)
                .cloned()
                .unwrap_or_else(|| TypeFragment::new("void")),
            payload_jni_type: payload
                .map(CallbackCompletionPayload::jni_type)
                .cloned()
                .unwrap_or_else(|| TypeFragment::new("void")),
            payload_bytes: payload.is_some_and(CallbackCompletionPayload::is_bytes),
            payload_record: payload.is_some_and(CallbackCompletionPayload::is_record),
            payload_callback_handle: payload
                .and_then(CallbackCompletionPayload::callback_handle_constructor)
                .is_some(),
            payload_create_handle: payload
                .and_then(CallbackCompletionPayload::callback_handle_constructor)
                .cloned(),
        }
    }
}
