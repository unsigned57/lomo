#[export]
pub trait Listener {
    fn on_value(&self, value: u32) -> u32;
    fn on_name(&self, name: &str);
}

#[export]
pub fn make_listener() -> Box<dyn Listener> {
    loop {}
}
