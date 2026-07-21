#[export]
pub trait Child {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub trait Listener {
    fn child(&self) -> Box<dyn Child>;
}
