use boltffi::*;

#[export]
pub fn add_i8(left: i8, right: i8) -> i8 {
    left.wrapping_add(right)
}

#[export]
pub fn add_u8(left: u8, right: u8) -> u8 {
    left.wrapping_add(right)
}

#[export]
pub fn add_i16(left: i16, right: i16) -> i16 {
    left.wrapping_add(right)
}

#[export]
pub fn add_u16(left: u16, right: u16) -> u16 {
    left.wrapping_add(right)
}

#[export]
pub fn add_i32(left: i32, right: i32) -> i32 {
    left.wrapping_add(right)
}

#[export]
pub fn add_u32(left: u32, right: u32) -> u32 {
    left.wrapping_add(right)
}

#[export]
pub fn add_i64(left: i64, right: i64) -> i64 {
    left.wrapping_add(right)
}

#[export]
pub fn add_u64(left: u64, right: u64) -> u64 {
    left.wrapping_add(right)
}

#[export]
pub fn add_isize(left: isize, right: isize) -> isize {
    left.wrapping_add(right)
}

#[export]
pub fn add_usize(left: usize, right: usize) -> usize {
    left.wrapping_add(right)
}

#[export]
pub fn mix_floats(left: f32, right: f64) -> f64 {
    f64::from(left) + right * 2.0
}

#[export]
pub fn toggle(flag: bool) -> bool {
    !flag
}

#[export]
pub fn read_ref(value: &i32) -> i32 {
    *value
}

#[export]
pub fn bump_in_place(value: &mut i32) {
    *value += 1;
}

#[export]
pub fn noop() {}
