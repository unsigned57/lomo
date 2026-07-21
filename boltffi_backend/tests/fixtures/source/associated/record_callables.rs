#[repr(C)]
#[data]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[data]
pub struct Person {
    pub name: String,
}

#[data(impl)]
impl Point {
    pub fn origin() -> Self {
        Self { x: 0.0, y: 0.0 }
    }

    pub fn distance(&self, other: Point) -> f64 {
        other.x - self.x
    }
}

#[data(impl)]
impl Person {
    pub fn rename(&self, name: String) -> String {
        name
    }
}
