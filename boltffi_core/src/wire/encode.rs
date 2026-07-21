use crate::wire::constants::*;
use crate::wire::shape::WireShape;
use crate::wire::temporal::{DurationWireValue, EpochTimestampWireValue};

#[cfg(feature = "chrono")]
use chrono::{DateTime, Utc};

use std::{
    collections::{BTreeMap, HashMap},
    time::{Duration, SystemTime},
};

#[cfg(feature = "uuid")]
use uuid::Uuid;

#[cfg(feature = "url")]
use url::Url;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WireEncodingKind {
    General,
    Blittable,
}

pub trait WireEncode {
    const ENCODING_KIND: WireEncodingKind = WireEncodingKind::General;

    fn is_fixed_size() -> bool
    where
        Self: Sized,
    {
        false
    }

    fn fixed_size() -> Option<usize>
    where
        Self: Sized,
    {
        None
    }

    fn wire_size(&self) -> usize;

    fn encode_to(&self, buf: &mut [u8]) -> usize;
}

struct WireWriter<'buffer> {
    buffer: &'buffer mut [u8],
    offset: usize,
}

impl<'buffer> WireWriter<'buffer> {
    #[inline]
    fn new(buffer: &'buffer mut [u8]) -> Self {
        Self { buffer, offset: 0 }
    }

    #[inline]
    fn write_byte(&mut self, value: u8) {
        self.buffer[self.offset] = value;
        self.offset += 1;
    }

    #[inline]
    fn write_bytes(&mut self, value: &[u8]) {
        let end = self.offset + value.len();
        self.buffer[self.offset..end].copy_from_slice(value);
        self.offset = end;
    }

    #[inline]
    fn write_value<T: WireEncode + ?Sized>(&mut self, value: &T) {
        let written = value.encode_to(&mut self.buffer[self.offset..]);
        self.offset += written;
    }

    #[inline]
    fn write_length_prefixed_bytes(&mut self, value: &[u8]) {
        self.write_bytes(&(value.len() as u32).to_le_bytes());
        self.write_bytes(value);
    }

    #[inline]
    fn write_count(&mut self, count: usize) {
        self.write_bytes(&(count as u32).to_le_bytes());
    }

    #[inline]
    fn finish(self) -> usize {
        self.offset
    }
}

macro_rules! impl_wire_primitive {
    ($($ty:ty),*) => {
        $(
            impl WireEncode for $ty {
                const ENCODING_KIND: WireEncodingKind = WireEncodingKind::Blittable;

                #[inline]
                fn is_fixed_size() -> bool {
                    <$ty as WireShape>::LAYOUT.is_fixed_size()
                }

                #[inline]
                fn fixed_size() -> Option<usize> {
                    <$ty as WireShape>::LAYOUT.fixed_size()
                }

                #[inline]
                fn wire_size(&self) -> usize {
                    core::mem::size_of::<$ty>()
                }

                #[inline]
                fn encode_to(&self, buf: &mut [u8]) -> usize {
                    let bytes = self.to_le_bytes();
                    buf[..bytes.len()].copy_from_slice(&bytes);
                    bytes.len()
                }
            }
        )*
    };
}

impl_wire_primitive!(i8, i16, i32, i64, u8, u16, u32, u64, f32, f64);

macro_rules! impl_casted_wire_encode {
    ($($ty:ty => $repr:ty),* $(,)?) => {
        $(
            impl WireEncode for $ty {
                const ENCODING_KIND: WireEncodingKind = WireEncodingKind::Blittable;

                #[inline]
                fn is_fixed_size() -> bool {
                    <$ty as WireShape>::LAYOUT.is_fixed_size()
                }

                #[inline]
                fn fixed_size() -> Option<usize> {
                    <$ty as WireShape>::LAYOUT.fixed_size()
                }

                #[inline]
                fn wire_size(&self) -> usize {
                    <$ty as WireShape>::LAYOUT.fixed_size().unwrap_or(0)
                }

                #[inline]
                fn encode_to(&self, buffer: &mut [u8]) -> usize {
                    let bytes = (*self as $repr).to_le_bytes();
                    buffer[..bytes.len()].copy_from_slice(&bytes);
                    bytes.len()
                }
            }
        )*
    };
}

impl_casted_wire_encode!(isize => i64, usize => u64);

impl WireEncode for bool {
    const ENCODING_KIND: WireEncodingKind = WireEncodingKind::Blittable;

    #[inline]
    fn is_fixed_size() -> bool {
        <bool as WireShape>::LAYOUT.is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        <bool as WireShape>::LAYOUT.fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        1
    }

    #[inline]
    fn encode_to(&self, buf: &mut [u8]) -> usize {
        buf[0] = if *self { 1 } else { 0 };
        1
    }
}

impl WireEncode for str {
    #[inline]
    fn wire_size(&self) -> usize {
        STRING_LEN_SIZE + self.len()
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        writer.write_length_prefixed_bytes(self.as_bytes());
        writer.finish()
    }
}

impl WireEncode for String {
    #[inline]
    fn wire_size(&self) -> usize {
        self.as_str().wire_size()
    }

    #[inline]
    fn encode_to(&self, buf: &mut [u8]) -> usize {
        self.as_str().encode_to(buf)
    }
}

impl WireEncode for Duration {
    #[inline]
    fn is_fixed_size() -> bool {
        <Duration as WireShape>::LAYOUT.is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        <Duration as WireShape>::LAYOUT.fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        12
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        DurationWireValue::from(*self).write_to(buffer)
    }
}

impl WireEncode for SystemTime {
    #[inline]
    fn is_fixed_size() -> bool {
        <SystemTime as WireShape>::LAYOUT.is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        <SystemTime as WireShape>::LAYOUT.fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        12
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        EpochTimestampWireValue::from(*self).write_to(buffer)
    }
}

#[cfg(feature = "uuid")]
impl WireEncode for Uuid {
    #[inline]
    fn is_fixed_size() -> bool {
        <Uuid as WireShape>::LAYOUT.is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        <Uuid as WireShape>::LAYOUT.fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        16
    }

    #[inline]
    fn encode_to(&self, buf: &mut [u8]) -> usize {
        let bytes = self.as_bytes();
        let hi = u64::from_be_bytes(bytes[..8].try_into().expect("uuid hi bytes"));
        let lo = u64::from_be_bytes(bytes[8..].try_into().expect("uuid lo bytes"));
        hi.encode_to(&mut buf[..8]);
        lo.encode_to(&mut buf[8..16]);
        16
    }
}

#[cfg(feature = "url")]
impl WireEncode for Url {
    #[inline]
    fn wire_size(&self) -> usize {
        self.as_str().wire_size()
    }

    #[inline]
    fn encode_to(&self, buf: &mut [u8]) -> usize {
        self.as_str().encode_to(buf)
    }
}

#[cfg(feature = "chrono")]
impl WireEncode for DateTime<Utc> {
    #[inline]
    fn is_fixed_size() -> bool {
        <DateTime<Utc> as WireShape>::LAYOUT.is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        <DateTime<Utc> as WireShape>::LAYOUT.fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        12
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        EpochTimestampWireValue::from(*self).write_to(buffer)
    }
}

impl<T: WireEncode> WireEncode for Option<T> {
    #[inline]
    fn wire_size(&self) -> usize {
        match self {
            Some(value) => OPTION_FLAG_SIZE + value.wire_size(),
            None => OPTION_FLAG_SIZE,
        }
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        match self {
            Some(value) => {
                writer.write_byte(1);
                writer.write_value(value);
            }
            None => writer.write_byte(0),
        }
        writer.finish()
    }
}

impl<T: WireEncode> WireEncode for Box<T> {
    const ENCODING_KIND: WireEncodingKind = T::ENCODING_KIND;

    #[inline]
    fn is_fixed_size() -> bool {
        T::is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        T::fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        (**self).wire_size()
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        (**self).encode_to(buffer)
    }
}

impl<T: WireEncode> WireEncode for Vec<T> {
    #[inline]
    fn wire_size(&self) -> usize {
        match T::ENCODING_KIND {
            WireEncodingKind::Blittable => VEC_COUNT_SIZE + self.len() * core::mem::size_of::<T>(),
            WireEncodingKind::General => {
                VEC_COUNT_SIZE + self.iter().map(WireEncode::wire_size).sum::<usize>()
            }
        }
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        writer.write_count(self.len());

        if self.is_empty() {
            return writer.finish();
        }

        match T::ENCODING_KIND {
            WireEncodingKind::Blittable => {
                let byte_count = self.len() * core::mem::size_of::<T>();
                unsafe {
                    core::ptr::copy_nonoverlapping(
                        self.as_ptr() as *const u8,
                        writer.buffer.as_mut_ptr().add(writer.offset),
                        byte_count,
                    );
                }
                writer.offset += byte_count;
                writer.finish()
            }
            WireEncodingKind::General => {
                self.iter().for_each(|element| writer.write_value(element));
                writer.finish()
            }
        }
    }
}

impl<T: WireEncode> WireEncode for [T] {
    #[inline]
    fn wire_size(&self) -> usize {
        match T::ENCODING_KIND {
            WireEncodingKind::Blittable => VEC_COUNT_SIZE + core::mem::size_of_val(self),
            WireEncodingKind::General => {
                VEC_COUNT_SIZE + self.iter().map(WireEncode::wire_size).sum::<usize>()
            }
        }
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        writer.write_count(self.len());

        if self.is_empty() {
            return writer.finish();
        }

        match T::ENCODING_KIND {
            WireEncodingKind::Blittable => {
                let byte_count = core::mem::size_of_val(self);
                unsafe {
                    core::ptr::copy_nonoverlapping(
                        self.as_ptr() as *const u8,
                        writer.buffer.as_mut_ptr().add(writer.offset),
                        byte_count,
                    );
                }
                writer.offset += byte_count;
                writer.finish()
            }
            WireEncodingKind::General => {
                self.iter().for_each(|element| writer.write_value(element));
                writer.finish()
            }
        }
    }
}

impl<K: WireEncode, V: WireEncode> WireEncode for HashMap<K, V> {
    #[inline]
    fn wire_size(&self) -> usize {
        VEC_COUNT_SIZE
            + self
                .iter()
                .map(|(key, value)| key.wire_size() + value.wire_size())
                .sum::<usize>()
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        writer.write_count(self.len());
        self.iter().for_each(|(key, value)| {
            writer.write_value(key);
            writer.write_value(value);
        });
        writer.finish()
    }
}

impl<K: WireEncode, V: WireEncode> WireEncode for BTreeMap<K, V> {
    #[inline]
    fn wire_size(&self) -> usize {
        VEC_COUNT_SIZE
            + self
                .iter()
                .map(|(key, value)| key.wire_size() + value.wire_size())
                .sum::<usize>()
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        writer.write_count(self.len());
        self.iter().for_each(|(key, value)| {
            writer.write_value(key);
            writer.write_value(value);
        });
        writer.finish()
    }
}

impl<T: WireEncode, E: WireEncode> WireEncode for Result<T, E> {
    #[inline]
    fn wire_size(&self) -> usize {
        match self {
            Ok(value) => RESULT_TAG_SIZE + value.wire_size(),
            Err(error) => RESULT_TAG_SIZE + error.wire_size(),
        }
    }

    #[inline]
    fn encode_to(&self, buffer: &mut [u8]) -> usize {
        let mut writer = WireWriter::new(buffer);
        match self {
            Ok(value) => {
                writer.write_byte(0);
                writer.write_value(value);
            }
            Err(err) => {
                writer.write_byte(1);
                writer.write_value(err);
            }
        }
        writer.finish()
    }
}

macro_rules! impl_wire_tuple {
    ($($name:ident : $index:tt),+ $(,)?) => {
        impl<$($name: WireEncode),+> WireEncode for ($($name,)+) {
            #[inline]
            fn wire_size(&self) -> usize {
                0 $(+ self.$index.wire_size())+
            }

            #[inline]
            fn encode_to(&self, buffer: &mut [u8]) -> usize {
                let mut writer = WireWriter::new(buffer);
                $(writer.write_value(&self.$index);)+
                writer.finish()
            }
        }
    };
}

impl_wire_tuple!(A: 0);
impl_wire_tuple!(A: 0, B: 1);
impl_wire_tuple!(A: 0, B: 1, C: 2);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5, G: 6);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5, G: 6, H: 7);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5, G: 6, H: 7, I: 8);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5, G: 6, H: 7, I: 8, J: 9);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5, G: 6, H: 7, I: 8, J: 9, K: 10);
impl_wire_tuple!(A: 0, B: 1, C: 2, D: 3, E: 4, F: 5, G: 6, H: 7, I: 8, J: 9, K: 10, L: 11);

impl WireEncode for () {
    #[inline]
    fn is_fixed_size() -> bool {
        <() as WireShape>::LAYOUT.is_fixed_size()
    }

    #[inline]
    fn fixed_size() -> Option<usize> {
        <() as WireShape>::LAYOUT.fixed_size()
    }

    #[inline]
    fn wire_size(&self) -> usize {
        0
    }

    #[inline]
    fn encode_to(&self, _buf: &mut [u8]) -> usize {
        0
    }
}

impl<T: WireEncode + ?Sized> WireEncode for &T {
    #[inline]
    fn wire_size(&self) -> usize {
        (*self).wire_size()
    }

    #[inline]
    fn encode_to(&self, buf: &mut [u8]) -> usize {
        (*self).encode_to(buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_primitives() {
        let mut buf = [0u8; 8];

        let written = 42i32.encode_to(&mut buf);
        assert_eq!(written, 4);
        assert_eq!(&buf[..4], &[42, 0, 0, 0]);

        let written = 3.5f64.encode_to(&mut buf);
        assert_eq!(written, 8);

        let written = true.encode_to(&mut buf);
        assert_eq!(written, 1);
        assert_eq!(buf[0], 1);

        let written = false.encode_to(&mut buf);
        assert_eq!(written, 1);
        assert_eq!(buf[0], 0);
    }

    #[test]
    fn encode_string() {
        let mut buf = [0u8; 32];
        let s = "hello";

        let written = s.encode_to(&mut buf);
        assert_eq!(written, 9); // 4 (len) + 5 (bytes)
        assert_eq!(&buf[..4], &[5, 0, 0, 0]); // len = 5
        assert_eq!(&buf[4..9], b"hello");
    }

    #[test]
    fn encode_option_some() {
        let mut buf = [0u8; 16];
        let opt: Option<i32> = Some(42);

        let written = opt.encode_to(&mut buf);
        assert_eq!(written, 5); // 1 (flag) + 4 (i32)
        assert_eq!(buf[0], 1); // is_some
        assert_eq!(&buf[1..5], &[42, 0, 0, 0]);
    }

    #[test]
    fn encode_option_none() {
        let mut buf = [0u8; 16];
        let opt: Option<i32> = None;

        let written = opt.encode_to(&mut buf);
        assert_eq!(written, 1);
        assert_eq!(buf[0], 0);
    }

    #[test]
    fn encode_vec_fixed_size() {
        let mut buf = [0u8; 32];
        let vec: Vec<i32> = vec![1, 2, 3];

        let written = vec.encode_to(&mut buf);
        assert_eq!(written, 16); // 4 (count) + 3 * 4 (elements)
        assert_eq!(&buf[..4], &[3, 0, 0, 0]); // count = 3
        assert_eq!(&buf[4..8], &[1, 0, 0, 0]);
        assert_eq!(&buf[8..12], &[2, 0, 0, 0]);
        assert_eq!(&buf[12..16], &[3, 0, 0, 0]);
    }

    #[test]
    fn encode_vec_variable_size() {
        let mut buf = [0u8; 64];
        let vec: Vec<String> = vec!["hi".to_string(), "there".to_string()];

        let written = vec.encode_to(&mut buf);
        assert_eq!(written, 4 + 6 + 9);
        assert_eq!(&buf[..4], &[2, 0, 0, 0]);
    }

    #[test]
    fn wire_size_calculations() {
        assert_eq!(42i32.wire_size(), 4);
        assert_eq!("hello".wire_size(), 9);
        assert_eq!(Some(42i32).wire_size(), 5);
        assert_eq!(None::<i32>.wire_size(), 1);

        let vec: Vec<i32> = vec![1, 2, 3];
        assert_eq!(vec.wire_size(), 16);

        let vec: Vec<String> = vec!["hi".to_string(), "there".to_string()];
        assert_eq!(vec.wire_size(), 4 + 6 + 9);
    }

    mod large_payloads {
        use super::*;

        #[test]
        fn large_string_1mb() {
            let size = 1024 * 1024;
            let large_string: String = "x".repeat(size);

            assert_eq!(large_string.wire_size(), 4 + size);

            let mut buf = vec![0u8; large_string.wire_size()];
            let written = large_string.encode_to(&mut buf);

            assert_eq!(written, 4 + size);
            assert_eq!(&buf[4..], large_string.as_bytes());
        }

        #[test]
        fn large_string_10mb() {
            let size = 10 * 1024 * 1024;
            let large_string: String = "y".repeat(size);

            assert_eq!(large_string.wire_size(), 4 + size);

            let mut buf = vec![0u8; large_string.wire_size()];
            let written = large_string.encode_to(&mut buf);

            assert_eq!(written, 4 + size);
        }

        #[test]
        fn large_vec_100k_elements() {
            let count = 100_000;
            let large_vec: Vec<i32> = (0..count).collect();

            assert_eq!(large_vec.wire_size(), 4 + count as usize * 4);

            let mut buf = vec![0u8; large_vec.wire_size()];
            let written = large_vec.encode_to(&mut buf);

            assert_eq!(written, 4 + count as usize * 4);

            let stored_count = u32::from_le_bytes(buf[..4].try_into().unwrap());
            assert_eq!(stored_count, count as u32);
        }

        #[test]
        fn large_vec_1m_elements() {
            let count = 1_000_000;
            let large_vec: Vec<i32> = (0..count).collect();

            let mut buf = vec![0u8; large_vec.wire_size()];
            let written = large_vec.encode_to(&mut buf);

            assert_eq!(written, 4 + count as usize * 4);
        }

        #[test]
        fn large_vec_of_strings() {
            let count = 10_000;
            let large_vec: Vec<String> = (0..count).map(|i| format!("item_{}", i)).collect();

            let expected_size: usize = 4 + large_vec.iter().map(|s| 4 + s.len()).sum::<usize>();
            assert_eq!(large_vec.wire_size(), expected_size);

            let mut buf = vec![0u8; large_vec.wire_size()];
            let written = large_vec.encode_to(&mut buf);

            assert_eq!(written, expected_size);
        }

        #[test]
        fn nested_large_structures() {
            let inner_count: usize = 1000;
            let outer_count: usize = 100;

            let nested: Vec<Vec<i32>> = (0..outer_count)
                .map(|_| (0..inner_count as i32).collect())
                .collect();

            let inner_size = 4 + inner_count * 4;
            let expected_size = 4 + outer_count * inner_size;
            assert_eq!(nested.wire_size(), expected_size);

            let mut buf = vec![0u8; nested.wire_size()];
            let written = nested.encode_to(&mut buf);

            assert_eq!(written, expected_size);
        }
    }

    mod unicode {
        use super::*;

        #[test]
        fn ascii_string() {
            let s = "Hello, World!";
            assert_eq!(s.wire_size(), 4 + 13);
        }

        #[test]
        fn emoji_string() {
            let s = "Hello 👋 World 🌍";
            assert_eq!(s.wire_size(), 4 + s.len());

            let mut buf = vec![0u8; s.wire_size()];
            s.encode_to(&mut buf);

            assert_eq!(&buf[4..], s.as_bytes());
        }

        #[test]
        fn cjk_characters() {
            let s = "你好世界";
            assert_eq!(s.len(), 12);
            assert_eq!(s.wire_size(), 4 + 12);

            let mut buf = vec![0u8; s.wire_size()];
            s.encode_to(&mut buf);

            assert_eq!(&buf[4..], s.as_bytes());
        }

        #[test]
        fn arabic_rtl_text() {
            let s = "مرحبا بالعالم";
            assert_eq!(s.wire_size(), 4 + s.len());

            let mut buf = vec![0u8; s.wire_size()];
            s.encode_to(&mut buf);

            assert_eq!(&buf[4..], s.as_bytes());
        }

        #[test]
        fn mixed_scripts() {
            let s = "Hello 你好 مرحبا 🎉";
            assert_eq!(s.wire_size(), 4 + s.len());

            let mut buf = vec![0u8; s.wire_size()];
            s.encode_to(&mut buf);

            assert_eq!(&buf[4..], s.as_bytes());
        }

        #[test]
        fn combining_characters() {
            let s = "é";
            assert_eq!(s.chars().count(), 1);
            assert_eq!(s.len(), 2);
            assert_eq!(s.wire_size(), 4 + 2);

            let combining = "e\u{0301}";
            assert_eq!(combining.chars().count(), 2);
            assert_eq!(combining.len(), 3);
            assert_eq!(combining.wire_size(), 4 + 3);
        }

        #[test]
        fn zero_width_joiner_emoji() {
            let family = "👨‍👩‍👧‍👦";
            assert_eq!(family.wire_size(), 4 + family.len());

            let mut buf = vec![0u8; family.wire_size()];
            family.encode_to(&mut buf);

            assert_eq!(&buf[4..], family.as_bytes());
        }

        #[test]
        fn empty_string() {
            let s = "";
            assert_eq!(s.wire_size(), 4);

            let mut buf = vec![0u8; 4];
            let written = s.encode_to(&mut buf);

            assert_eq!(written, 4);
            assert_eq!(&buf, &[0, 0, 0, 0]);
        }

        #[test]
        fn single_byte_boundary() {
            let s = "\u{7F}";
            assert_eq!(s.len(), 1);
            assert_eq!(s.wire_size(), 4 + 1);
        }

        #[test]
        fn two_byte_boundary() {
            let s = "\u{80}";
            assert_eq!(s.len(), 2);
            assert_eq!(s.wire_size(), 4 + 2);

            let s = "\u{7FF}";
            assert_eq!(s.len(), 2);
        }

        #[test]
        fn three_byte_boundary() {
            let s = "\u{800}";
            assert_eq!(s.len(), 3);

            let s = "\u{FFFF}";
            assert_eq!(s.len(), 3);
        }

        #[test]
        fn four_byte_boundary() {
            let s = "\u{10000}";
            assert_eq!(s.len(), 4);

            let s = "\u{10FFFF}";
            assert_eq!(s.len(), 4);
        }

        #[test]
        fn string_with_newlines_and_tabs() {
            let s = "line1\nline2\tcolumn";
            assert_eq!(s.wire_size(), 4 + s.len());

            let mut buf = vec![0u8; s.wire_size()];
            s.encode_to(&mut buf);

            assert_eq!(&buf[4..], s.as_bytes());
        }

        #[test]
        fn string_with_null_bytes() {
            let s = "hello\0world";
            assert_eq!(s.len(), 11);
            assert_eq!(s.wire_size(), 4 + 11);

            let mut buf = vec![0u8; s.wire_size()];
            s.encode_to(&mut buf);

            assert_eq!(&buf[4..], s.as_bytes());
        }
    }

    mod unit_type {
        use super::*;

        #[test]
        fn unit_wire_size_is_zero() {
            assert_eq!(().wire_size(), 0);
        }

        #[test]
        fn unit_is_fixed_size() {
            assert!(<()>::is_fixed_size());
            assert_eq!(<()>::fixed_size(), Some(0));
        }

        #[test]
        fn unit_encode_writes_nothing() {
            let mut buf = [0xFFu8; 4];
            let written = ().encode_to(&mut buf);
            assert_eq!(written, 0);
            assert_eq!(buf, [0xFF; 4]); // buffer untouched
        }

        #[test]
        fn result_ok_unit_wire_size() {
            let val: Result<(), String> = Ok(());
            assert_eq!(val.wire_size(), 1); // tag only, no payload
        }

        #[test]
        fn result_ok_unit_encode() {
            let mut buf = [0u8; 16];
            let val: Result<(), i32> = Ok(());
            let written = val.encode_to(&mut buf);
            assert_eq!(written, 1);
            assert_eq!(buf[0], 0); // Ok tag
        }
    }

    #[allow(unused_allocation)]
    mod box_type {
        use super::*;

        #[test]
        fn box_i32_wire_size() {
            let val = Box::new(42i32);
            assert_eq!(val.wire_size(), 4);
        }

        #[test]
        fn box_i32_is_fixed_size() {
            assert!(<Box<i32>>::is_fixed_size());
            assert_eq!(<Box<i32>>::fixed_size(), Some(4));
        }

        #[test]
        fn box_i32_encoding_kind_is_blittable() {
            assert_eq!(<Box<i32>>::ENCODING_KIND, WireEncodingKind::Blittable);
        }

        #[test]
        fn box_string_encoding_kind_is_general() {
            assert_eq!(<Box<String>>::ENCODING_KIND, WireEncodingKind::General);
        }

        #[test]
        fn box_string_not_fixed_size() {
            assert!(!<Box<String>>::is_fixed_size());
            assert_eq!(<Box<String>>::fixed_size(), None);
        }

        #[test]
        fn box_i32_encode_matches_bare() {
            let mut buf_boxed = [0u8; 4];
            let mut buf_bare = [0u8; 4];

            Box::new(42i32).encode_to(&mut buf_boxed);
            42i32.encode_to(&mut buf_bare);

            assert_eq!(buf_boxed, buf_bare);
        }

        #[test]
        fn box_string_encode_matches_bare() {
            let s = "hello".to_string();
            let mut buf_boxed = vec![0u8; s.wire_size()];
            let mut buf_bare = vec![0u8; s.wire_size()];

            Box::new(s.clone()).encode_to(&mut buf_boxed);
            s.encode_to(&mut buf_bare);

            assert_eq!(buf_boxed, buf_bare);
        }

        #[test]
        fn box_vec_encode_matches_bare() {
            let v: Vec<i32> = vec![1, 2, 3];
            let mut buf_boxed = vec![0u8; v.wire_size()];
            let mut buf_bare = vec![0u8; v.wire_size()];

            Box::new(v.clone()).encode_to(&mut buf_boxed);
            v.encode_to(&mut buf_bare);

            assert_eq!(buf_boxed, buf_bare);
        }
    }

    #[allow(clippy::assertions_on_constants)]
    mod blittable {
        use super::*;

        #[test]
        fn primitive_is_blittable() {
            assert_eq!(i32::ENCODING_KIND, WireEncodingKind::Blittable);
            assert_eq!(f64::ENCODING_KIND, WireEncodingKind::Blittable);
            assert_eq!(u8::ENCODING_KIND, WireEncodingKind::Blittable);
        }

        #[test]
        fn string_is_not_blittable() {
            assert_eq!(String::ENCODING_KIND, WireEncodingKind::General);
        }

        #[test]
        fn vec_i32_encoding_matches_raw_memory() {
            let vec: Vec<i32> = vec![1, 2, 3, 0x7FFFFFFF, -1];
            let mut buf = vec![0u8; vec.wire_size()];
            vec.encode_to(&mut buf);

            assert_eq!(&buf[0..4], &5u32.to_le_bytes());

            let expected_bytes: Vec<u8> = vec.iter().flat_map(|v| v.to_le_bytes()).collect();
            assert_eq!(&buf[4..], &expected_bytes);
        }

        #[test]
        fn vec_f64_encoding_matches_raw_memory() {
            let vec: Vec<f64> = vec![1.5, -2.25, std::f64::consts::PI];
            let mut buf = vec![0u8; vec.wire_size()];
            vec.encode_to(&mut buf);

            assert_eq!(&buf[0..4], &3u32.to_le_bytes());

            let expected_bytes: Vec<u8> = vec.iter().flat_map(|v| v.to_le_bytes()).collect();
            assert_eq!(&buf[4..], &expected_bytes);
        }

        #[test]
        fn empty_blittable_vec() {
            let vec: Vec<i32> = vec![];
            assert_eq!(vec.wire_size(), 4);

            let mut buf = vec![0u8; 4];
            let written = vec.encode_to(&mut buf);
            assert_eq!(written, 4);
            assert_eq!(&buf, &[0, 0, 0, 0]);
        }

        #[test]
        fn blittable_wire_size_is_exact() {
            let vec: Vec<i32> = vec![1, 2, 3];
            assert_eq!(vec.wire_size(), 4 + 3 * 4);

            let vec: Vec<f64> = vec![1.0, 2.0];
            assert_eq!(vec.wire_size(), 4 + 2 * 8);

            let vec: Vec<u8> = vec![1, 2, 3, 4, 5];
            assert_eq!(vec.wire_size(), 4 + 5);
        }
    }
}
