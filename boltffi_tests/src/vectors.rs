use boltffi::*;

use crate::{FixtureRect, FixtureStatus};

#[export]
pub fn sum_u32(values: Vec<u32>) -> u64 {
    values.iter().copied().map(u64::from).sum()
}

#[export]
pub fn halve_f64(values: Vec<f64>) -> Vec<f64> {
    values.into_iter().map(|value| value / 2.0).collect()
}

#[export]
pub fn bounding_box(rects: Vec<FixtureRect>) -> FixtureRect {
    rects
        .into_iter()
        .reduce(|left, right| {
            let left_max_x = left.x + left.width;
            let left_max_y = left.y + left.height;
            let right_max_x = right.x + right.width;
            let right_max_y = right.y + right.height;
            let x = left.x.min(right.x);
            let y = left.y.min(right.y);
            FixtureRect {
                x,
                y,
                width: left_max_x.max(right_max_x) - x,
                height: left_max_y.max(right_max_y) - y,
            }
        })
        .unwrap_or_else(FixtureRect::origin)
}

#[export]
pub fn join_labels(labels: Vec<String>) -> String {
    labels.join("|")
}

#[export]
pub fn split_labels(text: String) -> Vec<String> {
    text.split('|').map(str::to_string).collect()
}

#[export]
pub fn statuses(count: u32) -> Vec<FixtureStatus> {
    (0..count)
        .map(|index| match index % 4 {
            0 => FixtureStatus::Pending,
            1 => FixtureStatus::Active,
            2 => FixtureStatus::Completed,
            _ => FixtureStatus::Failed,
        })
        .collect()
}
