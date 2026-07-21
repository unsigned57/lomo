use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI8, AtomicU32, Ordering};
use std::task::{Context, Poll};

use boltffi::__private::{
    FfiStatus,
    rustfuture::{self, RustFuturePoll},
};

struct YieldingFuture {
    polls_remaining: u32,
    started: Arc<AtomicBool>,
    completed: Arc<AtomicBool>,
}

impl Future for YieldingFuture {
    type Output = i32;

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<i32> {
        self.started.store(true, Ordering::SeqCst);

        if self.polls_remaining == 0 {
            self.completed.store(true, Ordering::SeqCst);
            Poll::Ready(42)
        } else {
            self.polls_remaining -= 1;
            cx.waker().wake_by_ref();
            Poll::Pending
        }
    }
}

fn make_pending_future(
    started: Arc<AtomicBool>,
    completed: Arc<AtomicBool>,
) -> impl Future<Output = i32> + Send + 'static {
    YieldingFuture {
        polls_remaining: 100,
        started,
        completed,
    }
}

async fn make_instant_future() -> i32 {
    99
}

mod complete_returns_result_only_if_future_finished {
    use super::*;

    #[test]
    fn unpolled_future_has_no_result() {
        let handle = rustfuture::rust_future_new(make_instant_future());

        let result = unsafe { rustfuture::rust_future_complete::<i32>(handle) };

        assert!(
            matches!(result, Err(FfiStatus::INTERNAL_ERROR)),
            "complete() returns INTERNAL_ERROR when future was never polled to completion"
        );

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }

    #[test]
    fn polled_to_completion_has_result() {
        let handle = rustfuture::rust_future_new(make_instant_future());

        extern "C" fn noop(_: u64, _: RustFuturePoll) {}
        unsafe { rustfuture::rust_future_poll::<i32>(handle, noop, 0) };

        let result = unsafe { rustfuture::rust_future_complete::<i32>(handle) };

        assert_eq!(
            result,
            Ok(99),
            "complete() returns the ready value when future ran to completion"
        );

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }

    #[test]
    fn partially_polled_future_has_no_result() {
        let started = Arc::new(AtomicBool::new(false));
        let completed = Arc::new(AtomicBool::new(false));

        let handle = rustfuture::rust_future_new(make_pending_future(
            Arc::clone(&started),
            Arc::clone(&completed),
        ));

        extern "C" fn noop(_: u64, _: RustFuturePoll) {}
        unsafe { rustfuture::rust_future_poll::<i32>(handle, noop, 0) };

        assert!(started.load(Ordering::SeqCst), "future started executing");
        assert!(!completed.load(Ordering::SeqCst), "future not yet complete");

        let result = unsafe { rustfuture::rust_future_complete::<i32>(handle) };

        assert!(
            matches!(result, Err(FfiStatus::INTERNAL_ERROR)),
            "complete() returns INTERNAL_ERROR when future started but didn't finish"
        );

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }
}

mod cancel_stops_further_polling {
    use super::*;

    #[test]
    fn poll_after_cancel_does_not_execute_future() {
        static POLL_COUNT: AtomicU32 = AtomicU32::new(0);

        struct CountingFuture;
        impl Future for CountingFuture {
            type Output = i32;
            fn poll(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<i32> {
                POLL_COUNT.fetch_add(1, Ordering::SeqCst);
                Poll::Ready(42)
            }
        }

        POLL_COUNT.store(0, Ordering::SeqCst);

        let handle = rustfuture::rust_future_new(CountingFuture);

        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };

        extern "C" fn noop(_: u64, _: RustFuturePoll) {}
        unsafe { rustfuture::rust_future_poll::<i32>(handle, noop, 0) };

        assert_eq!(
            POLL_COUNT.load(Ordering::SeqCst),
            0,
            "future should not be polled after cancel"
        );

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }

    #[test]
    fn cancel_signals_ready_to_waiting_continuation() {
        static CALLBACK_STATUS: AtomicI8 = AtomicI8::new(-1);

        let started = Arc::new(AtomicBool::new(false));
        let completed = Arc::new(AtomicBool::new(false));

        let handle = rustfuture::rust_future_new(make_pending_future(
            Arc::clone(&started),
            Arc::clone(&completed),
        ));

        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };

        CALLBACK_STATUS.store(-1, Ordering::SeqCst);
        extern "C" fn capture(_: u64, status: RustFuturePoll) {
            CALLBACK_STATUS.store(status as i8, Ordering::SeqCst);
        }

        unsafe { rustfuture::rust_future_poll::<i32>(handle, capture, 0) };

        assert_eq!(
            CALLBACK_STATUS.load(Ordering::SeqCst),
            RustFuturePoll::Ready as i8,
            "cancelled future signals Ready so caller stops waiting"
        );

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }
}

mod cancel_does_not_discard_completed_work {
    use super::*;

    #[test]
    fn result_preserved_if_future_completed_before_cancel() {
        let handle = rustfuture::rust_future_new(make_instant_future());

        extern "C" fn noop(_: u64, _: RustFuturePoll) {}
        unsafe { rustfuture::rust_future_poll::<i32>(handle, noop, 0) };

        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };

        let result = unsafe { rustfuture::rust_future_complete(handle) };

        assert_eq!(
            result,
            Ok(99),
            "cancel() does not discard already-computed result (efficiency: don't throw away completed work)"
        );

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }
}

mod cancel_is_idempotent {
    use super::*;

    #[test]
    fn multiple_cancels_safe() {
        let handle = rustfuture::rust_future_new(make_instant_future());

        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };
        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };
        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }
}

mod free_cleans_up_properly {
    use super::*;

    #[test]
    fn free_on_unpolled_future() {
        let handle = rustfuture::rust_future_new(make_instant_future());
        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }

    #[test]
    fn free_on_completed_future() {
        let handle = rustfuture::rust_future_new(make_instant_future());

        extern "C" fn noop(_: u64, _: RustFuturePoll) {}
        unsafe { rustfuture::rust_future_poll::<i32>(handle, noop, 0) };

        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }

    #[test]
    fn free_on_cancelled_future() {
        let handle = rustfuture::rust_future_new(make_instant_future());

        unsafe { rustfuture::rust_future_cancel::<i32>(handle) };
        unsafe { rustfuture::rust_future_free::<i32>(handle) };
    }
}
