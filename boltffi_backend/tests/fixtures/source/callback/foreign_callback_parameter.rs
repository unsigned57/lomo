#[export]
pub trait Listener {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub fn install(listener: impl Listener) {}
