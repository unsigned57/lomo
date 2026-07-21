#[export]
pub trait Listener {
    fn make_handler(&self) -> impl Fn(u32) -> u32;
}

#[export]
pub fn make_listener() -> Box<dyn Listener> {
    loop {}
}
