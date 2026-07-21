use boltffi::*;

#[data]
#[derive(Clone, Copy, Debug, PartialEq, Default)]
#[repr(i32)]
pub enum FixtureStatus {
    #[default]
    Pending = 0,
    Active = 1,
    Completed = 2,
    Failed = 3,
}

#[data]
#[derive(Clone, Debug, PartialEq)]
pub enum FixtureShape {
    Dot,
    Line(f64),
    Rect { width: f64, height: f64 },
}

#[export]
pub fn next_status(status: FixtureStatus) -> FixtureStatus {
    match status {
        FixtureStatus::Pending => FixtureStatus::Active,
        FixtureStatus::Active => FixtureStatus::Completed,
        FixtureStatus::Completed => FixtureStatus::Failed,
        FixtureStatus::Failed => FixtureStatus::Pending,
    }
}

#[export]
pub fn area(shape: FixtureShape) -> f64 {
    match shape {
        FixtureShape::Dot => 0.0,
        FixtureShape::Line(length) => length,
        FixtureShape::Rect { width, height } => width * height,
    }
}

#[export]
pub fn widen(shape: FixtureShape, by: f64) -> FixtureShape {
    match shape {
        FixtureShape::Dot => FixtureShape::Line(by),
        FixtureShape::Line(length) => FixtureShape::Line(length + by),
        FixtureShape::Rect { width, height } => FixtureShape::Rect {
            width: width + by,
            height,
        },
    }
}
