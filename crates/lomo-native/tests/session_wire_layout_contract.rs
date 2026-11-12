//! Behavior Contract
//!
//! Capability: `BoltFFI` direct (all-primitive) session records cross the JNI wire with one byte
//! layout. Rust encodes direct records as `size_of::<T>()` raw bytes while the generated Kotlin
//! codec writes and reads their fields packed in declaration order; the two agree only when the
//! record carries no padding. Owning layer: `lomo-native`; priority: P0 (statistics read path).
//!
//! Scenarios:
//! - Given a statistics snapshot encoded field-by-field in declaration order (the generated Kotlin
//!   `writeTo(WireWriter)` codec), when the Rust wire decodes it, then the snapshot round-trips
//!   instead of failing silently (which made `session_statistics` return an empty buffer and the
//!   Kotlin reader throw `length=0; index=0`).
//! - Given the direct civil-date and date-count records nested in `SessionStatistics`, when their
//!   layout is inspected, then `size_of` equals the sum of their field widths (no padding).
//!
//! Observable outcomes: decoded `SessionStatisticsSnapshot` value, record byte sizes.
//! TDD proof: RED `cargo test -p lomo-native --test session_wire_layout_contract` failed with
//! `BufferTooSmall` decoding the 6-byte packed `SessionCivilDate` (Rust expected the padded
//! 8-byte `repr(C)` layout); GREEN after month/day widen to `u16`, removing the padding.
//! Excludes: generated Kotlin syntax, JNI glue, application statistics aggregation.

#[cfg(test)]
mod tests {
    use std::mem::{size_of, size_of_val};

    use boltffi::__private::wire;
    use lomo_native::{SessionCivilDate, SessionDateCount, SessionStatisticsSnapshot};

    fn packed_civil_date(date: SessionCivilDate, out: &mut Vec<u8>) {
        out.extend_from_slice(&date.year.to_le_bytes());
        out.extend_from_slice(&date.month.to_le_bytes());
        out.extend_from_slice(&date.day.to_le_bytes());
    }

    #[test]
    fn packed_statistics_snapshot_decodes_through_the_rust_wire() {
        let as_of = SessionCivilDate {
            year: 2026,
            month: 10,
            day: 13,
        };
        let zone = "Asia/Shanghai";
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&u32::try_from(zone.len()).unwrap_or(u32::MAX).to_le_bytes());
        bytes.extend_from_slice(zone.as_bytes());
        packed_civil_date(as_of, &mut bytes);

        let decoded = wire::decode::<SessionStatisticsSnapshot>(&bytes);

        assert!(decoded.is_ok(), "packed snapshot must decode: {decoded:?}");
        if let Ok(snapshot) = decoded {
            assert_eq!(snapshot.zone, zone);
            assert_eq!(snapshot.as_of, as_of);
        }
    }

    #[test]
    fn direct_session_date_records_carry_no_padding() {
        let date = SessionCivilDate {
            year: 2026,
            month: 1,
            day: 2,
        };
        let count = SessionDateCount {
            year: 2026,
            month: 1,
            day: 2,
            count: 3,
        };
        assert_eq!(
            size_of::<SessionCivilDate>(),
            size_of_val(&date.year) + size_of_val(&date.month) + size_of_val(&date.day),
        );
        assert_eq!(
            size_of::<SessionDateCount>(),
            size_of_val(&count.year)
                + size_of_val(&count.month)
                + size_of_val(&count.day)
                + size_of_val(&count.count),
        );
    }
}
