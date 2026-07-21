use crate::wire::constants::VEC_COUNT_SIZE;

pub unsafe trait Blittable: Copy + Sized {
    #[inline]
    fn encode_slice(slice: &[Self], buf: &mut [u8]) -> usize {
        let count = slice.len() as u32;
        buf[..VEC_COUNT_SIZE].copy_from_slice(&count.to_le_bytes());

        if slice.is_empty() {
            return VEC_COUNT_SIZE;
        }

        let byte_count = std::mem::size_of_val(slice);
        unsafe {
            std::ptr::copy_nonoverlapping(
                slice.as_ptr() as *const u8,
                buf.as_mut_ptr().add(VEC_COUNT_SIZE),
                byte_count,
            );
        }
        VEC_COUNT_SIZE + byte_count
    }

    #[inline]
    fn slice_wire_size(slice: &[Self]) -> usize {
        VEC_COUNT_SIZE + std::mem::size_of_val(slice)
    }

    #[inline]
    fn decode_slice(buf: &[u8]) -> Option<Vec<Self>> {
        if buf.len() < VEC_COUNT_SIZE {
            return None;
        }

        let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
        if count == 0 {
            return Some(Vec::new());
        }

        let byte_count = count * std::mem::size_of::<Self>();
        let required_len = VEC_COUNT_SIZE + byte_count;
        if buf.len() < required_len {
            return None;
        }

        let mut result = Vec::<Self>::with_capacity(count);
        unsafe {
            std::ptr::copy_nonoverlapping(
                buf.as_ptr().add(VEC_COUNT_SIZE),
                result.as_mut_ptr() as *mut u8,
                byte_count,
            );
            result.set_len(count);
        }
        Some(result)
    }

    #[inline]
    fn encode_value(value: &Self, buf: &mut [u8]) -> usize {
        let size = std::mem::size_of::<Self>();
        unsafe {
            std::ptr::copy_nonoverlapping(
                value as *const Self as *const u8,
                buf.as_mut_ptr(),
                size,
            );
        }
        size
    }

    #[inline]
    fn decode_value(buf: &[u8]) -> Option<Self> {
        if buf.len() < std::mem::size_of::<Self>() {
            return None;
        }
        Some(unsafe { std::ptr::read_unaligned(buf.as_ptr() as *const Self) })
    }
}

macro_rules! impl_blittable_primitives {
    ($($primitive:ty),* $(,)?) => {
        $(
            unsafe impl Blittable for $primitive {}
        )*
    };
}

impl_blittable_primitives!(
    i8, i16, i32, i64, u8, u16, u32, u64, f32, f64, bool, isize, usize
);
