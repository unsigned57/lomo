use std::cell::RefCell;
use std::collections::HashMap;
use std::task::Waker;

/// Callback ABI for async completions that carry a result payload.
///
/// `user_data` is an opaque caller-owned context pointer.
/// `status` reports whether the operation succeeded.
/// `result` carries the completed payload when the status allows one.
pub type AsyncCallback<Result> =
    extern "C" fn(user_data: *mut core::ffi::c_void, status: crate::FfiStatus, result: Result);

/// Callback ABI for async completions that only report status.
///
/// `user_data` is an opaque caller-owned context pointer.
/// `status` reports whether the operation succeeded.
pub type AsyncCallbackVoid =
    extern "C" fn(user_data: *mut core::ffi::c_void, status: crate::FfiStatus);

/// Callback ABI for async completions that return an owned FFI string.
///
/// `user_data` is an opaque caller-owned context pointer.
/// `status` reports whether the operation succeeded.
/// `result` carries the returned string payload.
pub type AsyncCallbackString = extern "C" fn(
    user_data: *mut core::ffi::c_void,
    status: crate::FfiStatus,
    result: crate::FfiString,
);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(transparent)]
pub struct AsyncCallbackRequestId(u32);

impl AsyncCallbackRequestId {
    pub fn as_u32(self) -> u32 {
        self.0
    }
}

#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AsyncCallbackCompletionCode {
    Completed = 0,
    Cancelled = -1,
    Panicked = -2,
}

impl From<i32> for AsyncCallbackCompletionCode {
    fn from(value: i32) -> Self {
        match value {
            0 => Self::Completed,
            -1 => Self::Cancelled,
            _ => Self::Panicked,
        }
    }
}

impl AsyncCallbackCompletionCode {
    pub fn is_success(self) -> bool {
        matches!(self, Self::Completed)
    }
}

#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AsyncCallbackCompletionResult {
    Accepted = 0,
    UnknownOrAlreadyCompleted = -1,
}

pub struct AsyncCallbackCompletion {
    pub code: AsyncCallbackCompletionCode,
    pub data: Vec<u8>,
}

struct PendingAsyncCallbackRequest {
    waker: Option<Waker>,
    completion: Option<AsyncCallbackCompletion>,
}

struct AsyncCallbackRegistryState {
    next_request_id: u32,
    pending_requests: HashMap<u32, PendingAsyncCallbackRequest>,
}

impl AsyncCallbackRegistryState {
    fn new() -> Self {
        Self {
            next_request_id: 1,
            pending_requests: HashMap::new(),
        }
    }

    fn allocate(&mut self) -> AsyncCallbackRequestId {
        let request_id = self.next_request_id;
        self.next_request_id = self.next_request_id.wrapping_add(1);
        if self.next_request_id == 0 {
            self.next_request_id = 1;
        }
        self.pending_requests.insert(
            request_id,
            PendingAsyncCallbackRequest {
                waker: None,
                completion: None,
            },
        );
        AsyncCallbackRequestId(request_id)
    }

    fn set_waker(&mut self, request_id: AsyncCallbackRequestId, waker: Waker) {
        if let Some(pending_request) = self.pending_requests.get_mut(&request_id.0) {
            pending_request.waker = Some(waker);
        }
    }

    fn complete(
        &mut self,
        request_id: AsyncCallbackRequestId,
        completion_code: AsyncCallbackCompletionCode,
        data: Vec<u8>,
    ) -> AsyncCallbackCompletionResult {
        let Some(pending_request) = self.pending_requests.get_mut(&request_id.0) else {
            return AsyncCallbackCompletionResult::UnknownOrAlreadyCompleted;
        };

        if pending_request.completion.is_some() {
            return AsyncCallbackCompletionResult::UnknownOrAlreadyCompleted;
        }

        pending_request.completion = Some(AsyncCallbackCompletion {
            code: completion_code,
            data,
        });

        if let Some(waker) = pending_request.waker.take() {
            waker.wake();
        }

        AsyncCallbackCompletionResult::Accepted
    }

    fn take_completion(
        &mut self,
        request_id: AsyncCallbackRequestId,
    ) -> Option<AsyncCallbackCompletion> {
        self.pending_requests
            .get_mut(&request_id.0)?
            .completion
            .take()
    }

    fn remove(&mut self, request_id: AsyncCallbackRequestId) {
        self.pending_requests.remove(&request_id.0);
    }

    fn cancel(&mut self, request_id: AsyncCallbackRequestId) -> bool {
        let Some(pending_request) = self.pending_requests.get_mut(&request_id.0) else {
            return false;
        };

        if pending_request.completion.is_some() {
            return false;
        }

        pending_request.completion = Some(AsyncCallbackCompletion {
            code: AsyncCallbackCompletionCode::Cancelled,
            data: Vec::new(),
        });

        if let Some(waker) = pending_request.waker.take() {
            waker.wake();
        }

        true
    }
}

thread_local! {
    static REGISTRY: RefCell<AsyncCallbackRegistryState> = RefCell::new(AsyncCallbackRegistryState::new());
}

pub struct AsyncCallbackRegistry;

impl AsyncCallbackRegistry {
    pub fn current() -> Self {
        Self
    }

    pub fn allocate(&self) -> AsyncCallbackRequestId {
        self.with_state(AsyncCallbackRegistryState::allocate)
    }

    pub fn set_waker(&self, request_id: AsyncCallbackRequestId, waker: Waker) {
        self.with_state(|registry_state| registry_state.set_waker(request_id, waker));
    }

    pub fn complete(
        &self,
        request_id: AsyncCallbackRequestId,
        completion_code: AsyncCallbackCompletionCode,
        data: Vec<u8>,
    ) -> AsyncCallbackCompletionResult {
        self.with_state(|registry_state| registry_state.complete(request_id, completion_code, data))
    }

    pub fn take_completion(
        &self,
        request_id: AsyncCallbackRequestId,
    ) -> Option<AsyncCallbackCompletion> {
        self.with_state(|registry_state| registry_state.take_completion(request_id))
    }

    pub fn remove(&self, request_id: AsyncCallbackRequestId) {
        self.with_state(|registry_state| registry_state.remove(request_id));
    }

    pub fn cancel(&self, request_id: AsyncCallbackRequestId) -> bool {
        self.with_state(|registry_state| registry_state.cancel(request_id))
    }

    #[cfg(target_arch = "wasm32")]
    pub unsafe fn complete_from_ffi(
        &self,
        request_id: u32,
        completion_code: i32,
        data_ptr: u32,
        data_len: u32,
        data_cap: u32,
    ) -> i32 {
        let request_id = AsyncCallbackRequestId(request_id);
        let completion_code = AsyncCallbackCompletionCode::from(completion_code);

        let data = if data_ptr != 0 && data_len > 0 {
            unsafe {
                let data_slice =
                    std::slice::from_raw_parts(data_ptr as *const u8, data_len as usize);
                data_slice.to_vec()
            }
        } else {
            Vec::new()
        };

        if data_ptr != 0 && data_cap > 0 {
            crate::wasm::boltffi_wasm_free_impl(data_ptr as usize, data_cap as usize);
        }

        self.complete(request_id, completion_code, data) as i32
    }

    fn with_state<Result>(
        &self,
        registry_access: impl FnOnce(&mut AsyncCallbackRegistryState) -> Result,
    ) -> Result {
        REGISTRY.with(|registry| registry_access(&mut registry.borrow_mut()))
    }
}

pub struct AsyncCallbackRequestGuard {
    request_id: AsyncCallbackRequestId,
}

impl AsyncCallbackRequestGuard {
    pub fn new(request_id: AsyncCallbackRequestId) -> Self {
        Self { request_id }
    }
}

impl Drop for AsyncCallbackRequestGuard {
    fn drop(&mut self) {
        AsyncCallbackRegistry::current().remove(self.request_id);
    }
}
