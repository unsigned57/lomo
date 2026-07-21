#[repr(C)]
#[data]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[export]
pub fn count(values: Vec<Point>) -> u32 {
    values.len() as u32
}
