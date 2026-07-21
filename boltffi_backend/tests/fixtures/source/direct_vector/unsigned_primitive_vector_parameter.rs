use boltffi::export;

#[export]
pub fn echo_u16(values: Vec<u16>) -> Vec<u16> {
    values
}

#[export]
pub fn echo_u32(values: Vec<u32>) -> Vec<u32> {
    values
}

#[export]
pub fn echo_u64(values: Vec<u64>) -> Vec<u64> {
    values
}
