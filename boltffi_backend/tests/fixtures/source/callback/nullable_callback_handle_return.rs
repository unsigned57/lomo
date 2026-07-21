use std::sync::Arc;

#[export]
pub trait Listener {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub fn optional_boxed_listener() -> Option<Box<dyn Listener>> {
    None
}

#[export]
pub fn optional_shared_listener() -> Option<Arc<dyn Listener>> {
    None
}
