#[repr(C)]
#[data]
pub struct Point {
    pub x: i32,
    pub y: i32,
}

#[export]
pub fn install(callback: impl Fn() -> Point) {}
