#[export]
pub trait Listener {
    fn name(&self) -> String;
}
