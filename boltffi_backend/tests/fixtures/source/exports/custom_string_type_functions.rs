custom_type!(
    pub Email,
    remote = EmailRust,
    repr = String,
    into_ffi = email_into_ffi,
    try_from_ffi = email_from_ffi
);

#[export]
pub fn keep_email(value: EmailRust) -> EmailRust {
    value
}
