#[repr(C)]
#[data]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[data(impl)]
impl Point {
    pub fn move_by(&mut self, dx: f64) {
        self.x += dx;
    }
}
