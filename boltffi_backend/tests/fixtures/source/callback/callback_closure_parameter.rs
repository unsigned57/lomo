#[export]
pub trait Listener {
    fn install(&self, callback: impl Fn(u32) -> u32);
}
