#[repr(C)]
#[data]
pub struct Point {
    pub x: i32,
    pub y: i32,
}

#[export]
pub trait Child {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub trait Listener {
    async fn name(&self) -> String;
    async fn point(&self) -> Point;
    async fn child(&self) -> Box<dyn Child>;
}

#[export]
pub fn make_listener() -> Box<dyn Listener> {
    loop {}
}
