//! Shared native invokers for async callback completion.
//!
//! Many async callback methods can complete with the same payload shape. The JNI
//! bridge does not need a separate native completion helper for every method
//! when the ABI is identical. It needs one stable success symbol and one stable
//! failure symbol per payload contract.
//!
//! This module builds and deduplicates those invokers from callback
//! registrations. Callback methods refer to the invoker they need; the source
//! template emits each invoker once.

use std::collections::{BTreeMap, btree_map::Entry};

use crate::{
    bridge::{
        c::Identifier,
        jni::{CallbackCompletionPayload, CallbackRegistration, JniSymbolName, JvmClassPath},
    },
    core::Result,
};

/// JNI native methods that complete an async callback invocation.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub struct CallbackCompletionInvoker {
    success_method: Identifier,
    success: JniSymbolName,
    failure_method: Identifier,
    failure: JniSymbolName,
    error_method: Option<Identifier>,
    error: Option<JniSymbolName>,
    payload: Option<CallbackCompletionPayload>,
}

impl CallbackCompletionInvoker {
    /// Builds the distinct completion invokers needed by registered callback traits.
    pub fn from_callbacks(
        class: &JvmClassPath,
        callbacks: &[CallbackRegistration],
    ) -> Result<Vec<Self>> {
        callbacks
            .iter()
            .flat_map(CallbackRegistration::methods)
            .flat_map(|method| method.completions().into_iter())
            .try_fold(BTreeMap::new(), |mut invokers, completion| {
                let key = completion
                    .payload()
                    .map_or_else(|| "Void".to_owned(), |payload| payload.suffix().to_owned());
                match invokers.entry(key.clone()) {
                    Entry::Vacant(entry) => {
                        entry.insert(Self::new(class, &key, completion.payload().cloned())?);
                    }
                    Entry::Occupied(_) => {}
                }
                Ok::<_, crate::core::Error>(invokers)
            })
            .map(BTreeMap::into_values)
            .map(Iterator::collect)
    }

    /// Returns the success native method symbol.
    pub fn success(&self) -> &JniSymbolName {
        &self.success
    }

    /// Returns the JVM success native method name.
    pub fn success_method(&self) -> &Identifier {
        &self.success_method
    }

    /// Returns the failure native method symbol.
    pub fn failure(&self) -> &JniSymbolName {
        &self.failure
    }

    /// Returns the JVM failure native method name.
    pub fn failure_method(&self) -> &Identifier {
        &self.failure_method
    }

    /// Returns the native method symbol that completes with a user error payload.
    pub fn error(&self) -> Option<&JniSymbolName> {
        self.error.as_ref()
    }

    /// Returns the JVM error native method name.
    pub fn error_method(&self) -> Option<&Identifier> {
        self.error_method.as_ref()
    }

    /// Returns the successful completion payload shape.
    pub fn payload(&self) -> Option<&CallbackCompletionPayload> {
        self.payload.as_ref()
    }

    fn new(
        class: &JvmClassPath,
        suffix: &str,
        payload: Option<CallbackCompletionPayload>,
    ) -> Result<Self> {
        let success_method =
            Identifier::parse(format!("boltffi_async_callback_complete_{suffix}"))?;
        let failure_method =
            Identifier::parse(format!("boltffi_async_callback_complete_{suffix}_failure"))?;
        let error_method = payload
            .as_ref()
            .map(|_| Identifier::parse(format!("boltffi_async_callback_complete_{suffix}_error")))
            .transpose()?;
        Ok(Self {
            success: JniSymbolName::native_method(class, success_method.as_str())?,
            failure: JniSymbolName::native_method(class, failure_method.as_str())?,
            error: error_method
                .as_ref()
                .map(|method| JniSymbolName::native_method(class, method.as_str()))
                .transpose()?,
            success_method,
            failure_method,
            error_method,
            payload,
        })
    }
}
