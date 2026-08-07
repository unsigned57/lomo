//! Behavior Contract:
//! - Unit under test: the repository-owned `BoltFFI` facade's macro-private status helpers.
//! - Owning layer: native FFI boundary.
//! - Priority tier: P0.
//! - Capability: generated `BoltFFI` exports can report bounded decode failures through the facade.
//!
//! Scenarios:
//! - Given a length-only decode failure, when the helper records it, then the facade exposes the
//!   exact structured message to the next last-error read.
//! - Given a displayable decode failure, when the helper records it, then the formatted cause and
//!   buffer length are observable through the same boundary.
//!
//! Observable outcomes:
//! - The consumed last-error strings returned by the facade.
//!
//! TDD proof:
//! - RED on `BoltFFI` v0.29.3 before the facade update because the new macro helpers were absent.
//!
//! Excludes:
//! - Generated Kotlin/JNI packaging and Android runtime loading.

#[cfg(test)]
mod tests {
    use boltffi::__private::{set_last_error_display, set_last_error_len, take_last_error};

    #[test]
    fn status_helpers_preserve_bounded_decode_context() {
        set_last_error_len("payload", "wire decode failed", 12);
        assert_eq!(
            take_last_error().as_deref(),
            Some("payload: wire decode failed (buf_len=12)"),
        );

        set_last_error_display("payload", "invalid UTF-8", &"bad bytes", 4);
        assert_eq!(
            take_last_error().as_deref(),
            Some("payload: invalid UTF-8: bad bytes (buf_len=4)"),
        );
    }
}
