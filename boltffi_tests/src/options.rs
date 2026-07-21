use boltffi::*;

use crate::{FixturePoint, FixtureRect};

#[export]
pub fn simple_maybe_double(x: i32) -> Option<i32> {
    if x > 0 { Some(x * 2) } else { None }
}

#[export]
pub fn maybe_double(x: Option<i32>) -> Option<i32> {
    x.map(|value| value * 2)
}

#[export]
pub fn maybe_scale(x: Option<f64>) -> Option<f64> {
    x.map(|value| value * 1.5)
}

#[export]
pub fn maybe_point(present: bool) -> Option<FixturePoint> {
    present.then_some(FixturePoint { x: 2.0, y: 3.0 })
}

#[export]
pub fn point_or_origin(point: Option<FixtureRect>) -> FixtureRect {
    point.unwrap_or_else(FixtureRect::origin)
}

#[export]
pub fn maybe_label(text: Option<String>) -> Option<String> {
    text.map(|value| format!("{value}:seen"))
}
