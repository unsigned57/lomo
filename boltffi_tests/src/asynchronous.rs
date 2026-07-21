use boltffi::*;

use crate::FixtureRect;

#[export]
pub async fn async_add(left: i32, right: i32) -> i32 {
    left + right
}

#[export]
pub async fn async_greet(name: String) -> String {
    format!("hello {name}")
}

#[export]
pub async fn async_make_rect(x: f64, y: f64) -> FixtureRect {
    FixtureRect {
        x,
        y,
        width: x.abs() + 1.0,
        height: y.abs() + 1.0,
    }
}

#[export]
pub async fn async_ping() {}
