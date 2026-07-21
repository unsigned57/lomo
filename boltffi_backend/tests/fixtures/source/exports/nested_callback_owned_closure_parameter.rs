#[export]
pub trait Listener {
    fn install(&self, callback: impl Fn(Box<dyn Fn(u32) -> u32>) -> u32);
}
