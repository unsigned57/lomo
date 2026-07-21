#[export]
pub fn install(callback: impl Fn(Box<dyn Fn(u32) -> u32>) -> u32) {}
