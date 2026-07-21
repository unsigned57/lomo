//! Native methods that call Rust-owned callback handles from the JVM.
//!
//! When Rust returns a callback handle to Java, Java receives only an opaque
//! `jlong`. Calling a method on that returned callback must go back through the
//! stored native vtable, not through the JVM callback implementation class used
//! for foreign callbacks.
//!
//! This module builds those handle-call methods from the same C callback slots
//! used by the lower bridge. Each method takes the handle token, unwraps the
//! stored callback, prepares the Java-provided arguments, and dispatches through
//! the callback vtable slot. Synchronous methods return directly. Async methods
//! receive completion callbacks. Closure returns use the shared closure-handle
//! machinery instead of inventing a returned-callback-only path.
//!
//! Keeping this here matters because returned callbacks are not foreign
//! callbacks. A foreign callback is implemented by the JVM and handed to Rust.
//! A returned callback is owned by Rust and called later by the JVM. Both use the
//! same callback declaration, but the ownership and dispatch direction are
//! different.

use crate::{
    bridge::{
        c::{self, ArgumentList, Expression, Identifier, Statement, TypeFragment},
        jni::{
            CallbackCompletionPayload, ClosureRegistration, JniSymbolName, JvmClassPath,
            NativeParameter, NativeReturn,
        },
    },
    core::{Error, Result},
};

const JNI_BRIDGE: &str = "jni";

/// A JNI native method that invokes one Rust-owned callback handle slot.
///
/// The method is Java-visible but Rust-owned: Java passes a `jlong` handle, the
/// generated C source recovers the stored callback object, and the call goes
/// through the native callback vtable.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackHandleMethod {
    symbol: JniSymbolName,
    method: Identifier,
    vtable_type: Identifier,
    slot: Identifier,
    call: CallbackHandleMethodCall,
    parameters: Vec<NativeParameter>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum CallbackHandleMethodCall {
    Synchronous(NativeReturn),
    Asynchronous(Box<CallbackHandleCompletion>),
    ClosureReturn(CallbackHandleClosureReturn),
}

/// Completion callback used by an async Rust-owned callback handle method.
///
/// The native handle method cannot return the async result directly. It gives
/// Rust a completion function and context, and Rust calls that function later
/// with either a success payload or a failure status.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackHandleCompletion {
    function: Identifier,
    context: Identifier,
    success_method: Identifier,
    success_method_id: Identifier,
    success_signature: String,
    failure_method: Identifier,
    failure_method_id: Identifier,
    payload: Option<CallbackCompletionPayload>,
}

/// Closure returned by a Rust-owned callback handle method.
///
/// The Rust callback vtable writes the native closure triple into a local
/// out-pointer. The JNI method wraps that triple with the shared closure-handle
/// allocator and returns the resulting `jlong` to Java.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackHandleClosureReturn {
    storage: Identifier,
    invoke_field: Statement,
    local: Identifier,
    new_handle: Identifier,
}

impl CallbackHandleMethod {
    /// Builds handle-call methods for a returned callback trait.
    pub fn from_callback(
        class: &JvmClassPath,
        callback: &c::Callback,
        callbacks: &[c::Callback],
        closures: &[ClosureRegistration],
    ) -> Result<Vec<Self>> {
        callback
            .methods()
            .iter()
            .map(|slot| Self::from_slot(class, callback, slot, callbacks, closures))
            .collect()
    }

    /// Returns the exported JNI symbol for this handle method.
    pub fn symbol(&self) -> &JniSymbolName {
        &self.symbol
    }

    /// Returns the JVM native method name.
    pub fn method(&self) -> &Identifier {
        &self.method
    }

    /// Returns the C callback vtable type.
    pub fn vtable_type(&self) -> &Identifier {
        &self.vtable_type
    }

    /// Returns the callback vtable slot name.
    pub fn slot(&self) -> &Identifier {
        &self.slot
    }

    /// Returns parameters after `JNIEnv*`, `jclass`, and callback handle.
    pub fn parameters(&self) -> &[NativeParameter] {
        &self.parameters
    }

    /// Returns the synchronous JNI return contract.
    pub fn synchronous_return(&self) -> Option<&NativeReturn> {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(returns) => Some(returns),
            CallbackHandleMethodCall::Asynchronous(_)
            | CallbackHandleMethodCall::ClosureReturn(_) => None,
        }
    }

    /// Returns whether this method returns no value.
    pub fn returns_void(&self) -> bool {
        matches!(
            &self.call,
            CallbackHandleMethodCall::Synchronous(NativeReturn::Void)
                | CallbackHandleMethodCall::Asynchronous(_)
        )
    }

    /// Returns whether this method needs an explicit `jboolean` cast.
    pub fn returns_boolean(&self) -> bool {
        matches!(&self.call, CallbackHandleMethodCall::Synchronous(returns) if returns.is_boolean())
    }

    /// Returns whether this method returns an owned byte buffer.
    pub fn returns_bytes(&self) -> bool {
        matches!(&self.call, CallbackHandleMethodCall::Synchronous(returns) if returns.is_bytes())
    }

    /// Returns whether this method returns a direct record byte array.
    pub fn returns_record(&self) -> bool {
        matches!(&self.call, CallbackHandleMethodCall::Synchronous(returns) if returns.is_record())
    }

    /// Returns whether this method returns a callback handle token.
    pub fn returns_callback(&self) -> bool {
        matches!(&self.call, CallbackHandleMethodCall::Synchronous(returns) if returns.is_callback())
    }

    /// Returns whether this method returns a closure handle token.
    pub fn returns_closure(&self) -> bool {
        matches!(&self.call, CallbackHandleMethodCall::ClosureReturn(_))
    }

    /// Returns whether this method checks a returned `FfiStatus`.
    pub fn checks_status(&self) -> bool {
        matches!(
            &self.call,
            CallbackHandleMethodCall::Synchronous(
                NativeReturn::Status | NativeReturn::StatusValue(_)
            )
        )
    }

    /// Returns whether this method checks an encoded error buffer.
    pub fn checks_error_buffer(&self) -> bool {
        matches!(
            &self.call,
            CallbackHandleMethodCall::Synchronous(NativeReturn::EncodedErrorValue(_))
        )
    }

    /// Returns the success value written through `return_out`.
    pub fn success_out(&self) -> Option<&crate::bridge::jni::SuccessOutReturn> {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(returns) => returns.success_out(),
            CallbackHandleMethodCall::Asynchronous(_)
            | CallbackHandleMethodCall::ClosureReturn(_) => None,
        }
    }

    /// Returns the async completion contract when the vtable slot completes later.
    pub fn completion(&self) -> Option<&CallbackHandleCompletion> {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(_) => None,
            CallbackHandleMethodCall::Asynchronous(completion) => Some(completion.as_ref()),
            CallbackHandleMethodCall::ClosureReturn(_) => None,
        }
    }

    /// Returns the closure-return contract when this method returns a closure.
    pub fn closure_return(&self) -> Option<&CallbackHandleClosureReturn> {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(_)
            | CallbackHandleMethodCall::Asynchronous(_) => None,
            CallbackHandleMethodCall::ClosureReturn(returned) => Some(returned),
        }
    }

    fn from_slot(
        class: &JvmClassPath,
        callback: &c::Callback,
        slot: &c::CallbackSlot,
        callbacks: &[c::Callback],
        closures: &[ClosureRegistration],
    ) -> Result<Self> {
        let completion = Self::completion_group(slot)?;
        let closure_return = Self::closure_return_group(slot)?;
        if completion.is_some() && closure_return.is_some() {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback handle method has completion and closure return groups",
            });
        }
        let method = Identifier::parse(Self::method_name(callback, slot.name()))?;
        let function = c::Function::new(
            method.as_str(),
            Self::method_parameters(slot, completion, closure_return),
            match completion {
                Some(_) => c::Type::Void,
                None if closure_return.is_some() => c::Type::Status,
                None => slot.returns().clone(),
            },
        )?;
        let call = match (completion, closure_return) {
            (Some(completion), None) => CallbackHandleMethodCall::Asynchronous(Box::new(
                CallbackHandleCompletion::from_group(callback, slot, completion, callbacks)?,
            )),
            (None, Some(returned)) => CallbackHandleMethodCall::ClosureReturn(
                CallbackHandleClosureReturn::from_group(returned, closures)?,
            ),
            (None, None) => CallbackHandleMethodCall::Synchronous(NativeReturn::from_c_type(
                function.returns(),
            )?),
            (Some(_), Some(_)) => {
                return Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "callback handle method has completion and closure return groups",
                });
            }
        };
        Ok(Self {
            symbol: JniSymbolName::native_method(class, function.name())?,
            method,
            vtable_type: Identifier::parse(callback.vtable().name())?,
            slot: slot.name().clone(),
            call,
            parameters: NativeParameter::from_c_function(&function, callbacks, closures)?,
        })
    }

    fn method_name(callback: &c::Callback, slot: &Identifier) -> String {
        let callback = callback
            .create_handle()
            .name()
            .strip_prefix("boltffi_create_callback_")
            .unwrap_or_else(|| callback.create_handle().name());
        format!("boltffi_callback_handle_{callback}_{slot}")
    }

    fn completion_group(slot: &c::CallbackSlot) -> Result<Option<&c::CallbackCompletionParameter>> {
        let mut completions = slot
            .parameter_groups()
            .iter()
            .filter_map(|group| match group {
                c::ParameterGroup::CallbackCompletion(completion) => Some(completion),
                _ => None,
            });
        let completion = completions.next();
        if completions.next().is_some() {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback handle method has more than one completion group",
            });
        }
        Ok(completion)
    }

    fn closure_return_group(slot: &c::CallbackSlot) -> Result<Option<&c::ClosureReturnParameter>> {
        let mut returns = slot
            .parameter_groups()
            .iter()
            .filter_map(|group| match group {
                c::ParameterGroup::ClosureReturn(returned) => Some(returned),
                _ => None,
            });
        let returned = returns.next();
        if returns.next().is_some() {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback handle method has more than one closure return group",
            });
        }
        Ok(returned)
    }

    fn method_parameters(
        slot: &c::CallbackSlot,
        completion: Option<&c::CallbackCompletionParameter>,
        closure_return: Option<&c::ClosureReturnParameter>,
    ) -> Vec<c::Parameter> {
        slot.parameters()
            .iter()
            .enumerate()
            .filter(|(index, _)| {
                *index != 0
                    && completion.is_none_or(|completion| {
                        *index != completion.callback().position()
                            && *index != completion.context().position()
                    })
                    && closure_return.is_none_or(|returned| *index != returned.output().position())
            })
            .map(|(_, parameter)| parameter.clone())
            .collect()
    }

    /// Returns the C arguments passed to the callback vtable slot.
    pub fn arguments(&self) -> Result<ArgumentList> {
        Ok(ArgumentList::from_iter(
            [Expression::new("callback_handle->handle")]
                .into_iter()
                .chain(
                    self.parameters
                        .iter()
                        .map(NativeParameter::c_arguments)
                        .collect::<Result<Vec<_>>>()?
                        .into_iter()
                        .flatten(),
                )
                .chain(
                    self.completion()
                        .into_iter()
                        .flat_map(CallbackHandleCompletion::c_arguments),
                )
                .chain(
                    self.closure_return()
                        .into_iter()
                        .map(CallbackHandleClosureReturn::c_argument),
                ),
        ))
    }

    /// Returns the expression returned from the JNI method.
    pub fn return_value(&self, value: Expression) -> Result<Expression> {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(returns) => returns.return_expression(value),
            CallbackHandleMethodCall::Asynchronous(_) => Ok(value),
            CallbackHandleMethodCall::ClosureReturn(returned) => Ok(returned.handle_expression()),
        }
    }

    /// Returns the JNI method return type as C syntax.
    pub fn jni_type(&self) -> TypeFragment {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(returns) => returns.jni_type(),
            CallbackHandleMethodCall::Asynchronous(_) => TypeFragment::new("void"),
            CallbackHandleMethodCall::ClosureReturn(_) => TypeFragment::new("jlong"),
        }
    }

    /// Returns the temporary C result type used inside the method body.
    pub fn c_result_type(&self) -> Result<TypeFragment> {
        match &self.call {
            CallbackHandleMethodCall::Synchronous(returns) => returns.c_result_type(),
            CallbackHandleMethodCall::Asynchronous(_) => Ok(TypeFragment::new("void")),
            CallbackHandleMethodCall::ClosureReturn(_) => TypeFragment::anonymous(&c::Type::Status),
        }
    }
}

impl CallbackHandleCompletion {
    /// Returns the generated C completion callback function.
    pub fn function(&self) -> &Identifier {
        &self.function
    }

    /// Returns the JNI method parameter carrying callback completion data.
    pub fn context(&self) -> &Identifier {
        &self.context
    }

    /// Returns the JVM static success method.
    pub fn success_method(&self) -> &Identifier {
        &self.success_method
    }

    /// Returns the cached success method id symbol.
    pub fn success_method_id(&self) -> &Identifier {
        &self.success_method_id
    }

    /// Returns the JVM success method descriptor.
    pub fn success_signature(&self) -> &str {
        &self.success_signature
    }

    /// Returns the JVM static failure method.
    pub fn failure_method(&self) -> &Identifier {
        &self.failure_method
    }

    /// Returns the cached failure method id symbol.
    pub fn failure_method_id(&self) -> &Identifier {
        &self.failure_method_id
    }

    /// Returns the success payload carried by this completion.
    pub fn payload(&self) -> Option<&CallbackCompletionPayload> {
        self.payload.as_ref()
    }

    fn from_group(
        callback: &c::Callback,
        slot: &c::CallbackSlot,
        group: &c::CallbackCompletionParameter,
        callbacks: &[c::Callback],
    ) -> Result<Self> {
        let stem = callback.vtable().name();
        let payload = Self::completion_payload(slot, group, callbacks)?;
        Ok(Self {
            function: Identifier::parse(format!(
                "{stem}_{slot}_handle_completion",
                slot = slot.name()
            ))?,
            context: Identifier::parse("callback_data")?,
            success_method: Identifier::parse(format!("complete_{slot}", slot = slot.name()))?,
            success_method_id: Identifier::parse(format!(
                "g_{stem}_{slot}_success_method",
                slot = slot.name()
            ))?,
            success_signature: Self::method_signature(payload.as_ref()),
            failure_method: Identifier::parse(format!("fail_{slot}", slot = slot.name()))?,
            failure_method_id: Identifier::parse(format!(
                "g_{stem}_{slot}_failure_method",
                slot = slot.name()
            ))?,
            payload,
        })
    }

    fn completion_payload(
        slot: &c::CallbackSlot,
        group: &c::CallbackCompletionParameter,
        callbacks: &[c::Callback],
    ) -> Result<Option<CallbackCompletionPayload>> {
        match slot.parameter(group.callback()).ty() {
            c::Type::FunctionPointer { returns, params } => match params.as_slice() {
                [c::Type::MutPointer(context), c::Type::Status]
                    if matches!(returns.as_ref(), c::Type::Void)
                        && matches!(context.as_ref(), c::Type::Void) =>
                {
                    Ok(None)
                }
                [c::Type::MutPointer(context), c::Type::Status, payload]
                    if matches!(returns.as_ref(), c::Type::Void)
                        && matches!(context.as_ref(), c::Type::Void) =>
                {
                    CallbackCompletionPayload::from_c_type(payload, callbacks).map(Some)
                }
                _ => Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "callback handle completion has unsupported callback signature",
                }),
            },
            _ => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback handle completion parameter is not a function pointer",
            }),
        }
    }

    fn method_signature(payload: Option<&CallbackCompletionPayload>) -> String {
        format!(
            "(J{})V",
            payload
                .map(CallbackCompletionPayload::jni_signature)
                .unwrap_or_default()
        )
    }

    fn c_arguments(&self) -> [Expression; 2] {
        [
            Expression::identifier(self.function.clone()),
            Expression::cast(
                TypeFragment::new("void *"),
                Expression::cast(
                    TypeFragment::new("uintptr_t"),
                    Expression::identifier(self.context.clone()),
                ),
            ),
        ]
    }
}

impl CallbackHandleClosureReturn {
    /// Returns the C storage type used for the native closure triple.
    pub fn storage(&self) -> &Identifier {
        &self.storage
    }

    /// Returns the closure invoke field declaration.
    pub fn invoke_field(&self) -> &Statement {
        &self.invoke_field
    }

    /// Returns the local closure-return storage identifier.
    pub fn local(&self) -> &Identifier {
        &self.local
    }

    /// Returns the shared closure-handle allocation helper.
    pub fn new_handle(&self) -> &Identifier {
        &self.new_handle
    }

    fn from_group(
        returned: &c::ClosureReturnParameter,
        closures: &[ClosureRegistration],
    ) -> Result<Self> {
        let registration = closures
            .iter()
            .find(|registration| registration.signature() == returned.signature())
            .ok_or(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback handle closure return has no JNI closure registration",
            })?;
        let handle = registration
            .callback_handle()
            .ok_or(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback handle closure return has no closure handle registration",
            })?;
        Ok(Self {
            storage: Identifier::parse(format!(
                "BoltFFIJniClosureReturn{}",
                returned.signature().as_str()
            ))?,
            invoke_field: TypeFragment::declaration(returned.call_type(), "invoke")?,
            local: Identifier::parse("__boltffi_return")?,
            new_handle: handle.new_function().clone(),
        })
    }

    fn c_argument(&self) -> Expression {
        Expression::address_of(Expression::identifier(self.local.clone()))
    }

    fn handle_expression(&self) -> Expression {
        Expression::call(
            self.new_handle.clone(),
            ArgumentList::from_iter([
                Expression::new("env"),
                Expression::new(format!("{}.invoke", self.local)),
                Expression::new(format!("{}.context", self.local)),
                Expression::new(format!("{}.release", self.local)),
            ]),
        )
    }
}
