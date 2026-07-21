use std::sync::Arc;

#[export]
pub trait Child {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub trait Listener {
    fn optional_boxed_child(&self) -> Option<Box<dyn Child>>;
    fn optional_shared_child(&self) -> Option<Arc<dyn Child>>;
}
