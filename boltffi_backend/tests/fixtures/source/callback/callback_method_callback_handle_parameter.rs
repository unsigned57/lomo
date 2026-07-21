#[export]
pub trait Child {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub trait Listener {
    fn on_child(&self, child: Box<dyn Child>);
}
