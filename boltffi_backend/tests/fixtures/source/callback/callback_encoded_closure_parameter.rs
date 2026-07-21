#[export]
pub trait Listener {
    fn install(&self, callback: impl Fn(String) -> String);
}
