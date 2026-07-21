#[export]
pub trait Listener {
    async fn load(&self, key: u32) -> u32;
}

#[export]
pub fn make_listener() -> Box<dyn Listener> {
    loop {}
}
