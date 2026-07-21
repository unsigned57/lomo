#[repr(C)]
#[data]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[export]
pub fn points() -> Vec<Point> {
    Vec::new()
}
