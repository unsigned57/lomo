use std::convert::TryFrom;
use std::marker::PhantomData;
use std::ptr;
use std::sync::atomic::{AtomicPtr, AtomicU8, AtomicU64, Ordering};

pub type ContinuationCallback<Signal> = extern "C" fn(u64, Signal);

/// Maps scheduler events onto the signal type expected by one runtime consumer.
///
/// The scheduler itself only knows when a parked continuation is displaced,
/// woken, or cancelled. The policy defines which signal should be delivered
/// for each of those events.
pub trait ContinuationSignalPolicy {
    type Signal: Copy;

    fn displaced() -> Self::Signal;
    fn wake() -> Self::Signal;
    fn cancelled() -> Self::Signal;
}

/// State machine for a single continuation slot.
///
/// `Empty` means no continuation is parked and no wake is buffered.
/// `Waked` means a wake arrived before a continuation was parked.
/// `Stored` means one continuation is currently parked.
/// `Cancelled` is terminal and rejects future stores.
#[repr(u8)]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum ContinuationSlotState {
    Empty = 0,
    Waked = 1,
    Stored = 2,
    Cancelled = 3,
}

impl ContinuationSlotState {
    const fn as_repr(self) -> u8 {
        self as u8
    }
}

impl From<ContinuationSlotState> for u8 {
    fn from(state: ContinuationSlotState) -> Self {
        state.as_repr()
    }
}

impl TryFrom<u8> for ContinuationSlotState {
    type Error = u8;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::Empty),
            1 => Ok(Self::Waked),
            2 => Ok(Self::Stored),
            3 => Ok(Self::Cancelled),
            _ => Err(value),
        }
    }
}

#[derive(Clone, Copy)]
struct StoredContinuation<Signal: Copy> {
    callback: ContinuationCallback<Signal>,
    callback_data: u64,
}

impl<Signal: Copy> StoredContinuation<Signal> {
    fn from_raw_parts(callback_ptr: *mut (), callback_data: u64) -> Option<Self> {
        (!callback_ptr.is_null()).then(|| Self {
            callback: unsafe {
                std::mem::transmute::<*mut (), ContinuationCallback<Signal>>(callback_ptr)
            },
            callback_data,
        })
    }

    fn into_raw_parts(self) -> (*mut (), u64) {
        (self.callback as *mut (), self.callback_data)
    }

    fn invoke(self, signal: Signal) {
        (self.callback)(self.callback_data, signal);
    }
}

/// Lock-free handoff between one parked continuation and one buffered wake.
///
/// The scheduler owns the atomic state machine and callback storage.
/// Consumers supply a policy that maps scheduler events onto their own signal type.
///
/// The key guarantee is that a wake is not lost if it arrives before a continuation
/// is stored. In that case the state moves to `Waked`, and the next store receives
/// the wake signal immediately.
pub struct ContinuationScheduler<Policy: ContinuationSignalPolicy> {
    state: AtomicU8,
    callback_ptr: AtomicPtr<()>,
    callback_data: AtomicU64,
    policy: PhantomData<Policy>,
}

impl<Policy: ContinuationSignalPolicy> ContinuationScheduler<Policy> {
    pub fn new() -> Self {
        Self {
            state: AtomicU8::new(ContinuationSlotState::Empty.into()),
            callback_ptr: AtomicPtr::new(ptr::null_mut()),
            callback_data: AtomicU64::new(0),
            policy: PhantomData,
        }
    }

    /// Parks a continuation or delivers an immediate signal if a wake or cancellation
    /// was already observed.
    ///
    /// If another continuation is already parked, that older continuation is displaced
    /// with the policy's displaced signal and replaced by the new one.
    pub fn store_continuation(
        &self,
        callback: ContinuationCallback<Policy::Signal>,
        callback_data: u64,
    ) {
        let stored_continuation = StoredContinuation {
            callback,
            callback_data,
        };

        loop {
            match self.current_state() {
                ContinuationSlotState::Empty => {
                    self.write_continuation(stored_continuation);
                    if self
                        .try_transition(ContinuationSlotState::Empty, ContinuationSlotState::Stored)
                    {
                        return;
                    }
                }
                ContinuationSlotState::Waked => {
                    if self
                        .try_transition(ContinuationSlotState::Waked, ContinuationSlotState::Empty)
                    {
                        stored_continuation.invoke(Policy::wake());
                        return;
                    }
                }
                ContinuationSlotState::Stored => {
                    self.invoke_stored(Policy::displaced());
                    self.write_continuation(stored_continuation);
                    return;
                }
                ContinuationSlotState::Cancelled => {
                    stored_continuation.invoke(Policy::cancelled());
                    return;
                }
            }
        }
    }

    /// Delivers the wake signal to the parked continuation, or buffers one wake if no
    /// continuation is currently stored.
    pub fn wake(&self) {
        loop {
            match self.current_state() {
                ContinuationSlotState::Stored => {
                    if self
                        .try_transition(ContinuationSlotState::Stored, ContinuationSlotState::Empty)
                    {
                        self.invoke_stored(Policy::wake());
                        return;
                    }
                }
                ContinuationSlotState::Empty => {
                    if self
                        .try_transition(ContinuationSlotState::Empty, ContinuationSlotState::Waked)
                    {
                        return;
                    }
                }
                ContinuationSlotState::Waked | ContinuationSlotState::Cancelled => return,
            }
        }
    }

    /// Marks the scheduler as terminal.
    ///
    /// If a continuation is parked, it is invoked once with the policy's cancelled
    /// signal. Future stores observe cancellation immediately.
    pub fn cancel(&self) {
        loop {
            let current_state = self.current_state();
            match current_state {
                ContinuationSlotState::Stored => {
                    if self.try_transition(
                        ContinuationSlotState::Stored,
                        ContinuationSlotState::Cancelled,
                    ) {
                        self.invoke_stored(Policy::cancelled());
                        return;
                    }
                }
                ContinuationSlotState::Empty | ContinuationSlotState::Waked => {
                    if self.try_transition(current_state, ContinuationSlotState::Cancelled) {
                        return;
                    }
                }
                ContinuationSlotState::Cancelled => return,
            }
        }
    }

    pub fn is_cancelled(&self) -> bool {
        self.current_state() == ContinuationSlotState::Cancelled
    }

    fn current_state(&self) -> ContinuationSlotState {
        ContinuationSlotState::try_from(self.state.load(Ordering::Acquire))
            .unwrap_or(ContinuationSlotState::Empty)
    }

    fn try_transition(&self, from: ContinuationSlotState, to: ContinuationSlotState) -> bool {
        self.state
            .compare_exchange(from.into(), to.into(), Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }

    fn write_continuation(&self, stored_continuation: StoredContinuation<Policy::Signal>) {
        let (callback_ptr, callback_data) = stored_continuation.into_raw_parts();
        self.callback_data.store(callback_data, Ordering::Release);
        self.callback_ptr.store(callback_ptr, Ordering::Release);
    }

    fn load_continuation(&self) -> Option<StoredContinuation<Policy::Signal>> {
        let callback_ptr = self.callback_ptr.load(Ordering::Acquire);
        let callback_data = self.callback_data.load(Ordering::Acquire);
        StoredContinuation::from_raw_parts(callback_ptr, callback_data)
    }

    fn invoke_stored(&self, signal: Policy::Signal) {
        if let Some(stored_continuation) = self.load_continuation() {
            stored_continuation.invoke(signal);
        }
    }
}

impl<Policy: ContinuationSignalPolicy> Default for ContinuationScheduler<Policy> {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Mutex, MutexGuard, OnceLock};

    use super::{ContinuationScheduler, ContinuationSignalPolicy};

    #[repr(i8)]
    #[derive(Clone, Copy, PartialEq, Eq, Debug)]
    enum TestSignal {
        Displaced,
        Ready,
        Cancelled,
    }

    struct TestSignalPolicy;

    impl ContinuationSignalPolicy for TestSignalPolicy {
        type Signal = TestSignal;

        fn displaced() -> Self::Signal {
            TestSignal::Displaced
        }

        fn wake() -> Self::Signal {
            TestSignal::Ready
        }

        fn cancelled() -> Self::Signal {
            TestSignal::Cancelled
        }
    }

    fn invocation_log() -> &'static Mutex<Vec<(u64, TestSignal)>> {
        static INVOCATION_LOG: OnceLock<Mutex<Vec<(u64, TestSignal)>>> = OnceLock::new();
        INVOCATION_LOG.get_or_init(|| Mutex::new(Vec::new()))
    }

    fn test_guard() -> &'static Mutex<()> {
        static TEST_GUARD: OnceLock<Mutex<()>> = OnceLock::new();
        TEST_GUARD.get_or_init(|| Mutex::new(()))
    }

    fn lock_unpoisoned<T>(mutex: &'static Mutex<T>) -> MutexGuard<'static, T> {
        match mutex.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    extern "C" fn test_callback(callback_data: u64, signal: TestSignal) {
        lock_unpoisoned(invocation_log()).push((callback_data, signal));
    }

    fn take_invocations() -> Vec<(u64, TestSignal)> {
        let mut invocation_log = lock_unpoisoned(invocation_log());
        std::mem::take(&mut *invocation_log)
    }

    #[test]
    fn wake_after_store_invokes_ready_signal() {
        let _guard = lock_unpoisoned(test_guard());
        let scheduler = ContinuationScheduler::<TestSignalPolicy>::new();
        take_invocations();

        scheduler.store_continuation(test_callback, 7);
        scheduler.wake();

        assert_eq!(take_invocations(), vec![(7, TestSignal::Ready)]);
    }

    #[test]
    fn wake_before_store_invokes_ready_signal_immediately() {
        let _guard = lock_unpoisoned(test_guard());
        let scheduler = ContinuationScheduler::<TestSignalPolicy>::new();
        take_invocations();

        scheduler.wake();
        scheduler.store_continuation(test_callback, 9);

        assert_eq!(take_invocations(), vec![(9, TestSignal::Ready)]);
    }

    #[test]
    fn replacing_stored_continuation_invokes_displaced_signal() {
        let _guard = lock_unpoisoned(test_guard());
        let scheduler = ContinuationScheduler::<TestSignalPolicy>::new();
        take_invocations();

        scheduler.store_continuation(test_callback, 3);
        scheduler.store_continuation(test_callback, 4);

        assert_eq!(take_invocations(), vec![(3, TestSignal::Displaced)]);
    }

    #[test]
    fn cancellation_invokes_cancelled_signal() {
        let _guard = lock_unpoisoned(test_guard());
        let scheduler = ContinuationScheduler::<TestSignalPolicy>::new();
        take_invocations();

        scheduler.store_continuation(test_callback, 11);
        scheduler.cancel();

        assert_eq!(take_invocations(), vec![(11, TestSignal::Cancelled)]);
    }
}
