#[export]
pub fn maybe_add(value: Option<i32>) -> Option<u32> {
    value.map(|value| value as u32 + 1)
}
