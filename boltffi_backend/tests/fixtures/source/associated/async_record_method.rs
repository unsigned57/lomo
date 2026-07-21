#[repr(C)]
#[data]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[data(impl)]
impl Point {
    pub async fn compute(&self) -> f64 {
        self.x
    }
}
