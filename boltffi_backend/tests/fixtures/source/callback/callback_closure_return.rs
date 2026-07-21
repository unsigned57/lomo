#[export]
pub trait Listener {
    fn make_handler(&self) -> impl Fn(u32) -> u32;
}
