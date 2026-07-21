#[export]
pub trait Listener {
    fn install(&self, callback: impl Fn(Vec<u32>) -> u32);
}
