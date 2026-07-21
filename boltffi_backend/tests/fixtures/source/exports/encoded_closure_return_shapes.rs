#[export]
pub fn install_vec(callback: impl Fn() -> Vec<u32>) {}

#[export]
pub fn install_option(callback: impl Fn() -> Option<i32>) {}
