custom_type!(
    pub Timestamp,
    remote = TimestampRust,
    repr = i64,
    into_ffi = timestamp_into_ffi,
    try_from_ffi = timestamp_from_ffi
);

#[export]
pub fn keep_timestamp(value: TimestampRust) -> TimestampRust {
    value
}
