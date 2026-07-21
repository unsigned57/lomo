#[export]
pub trait Listener {
    fn next(&self) -> Option<u32>;
}
