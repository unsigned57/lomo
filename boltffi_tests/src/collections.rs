use std::collections::HashMap;

use boltffi::*;

#[export]
pub fn tally(labels: HashMap<String, i32>) -> i32 {
    labels.values().copied().sum()
}

#[export]
pub fn invert(labels: HashMap<String, i32>) -> HashMap<String, i32> {
    labels
        .into_iter()
        .map(|(label, value)| (label.chars().rev().collect::<String>(), -value))
        .collect()
}

#[export]
pub fn pair_up(value: i32, text: String) -> (i32, String) {
    (value * 2, format!("{text}:{value}"))
}

#[export]
pub fn deep(values: Vec<Option<String>>) -> u32 {
    values
        .into_iter()
        .flatten()
        .map(|value| value.len() as u32)
        .sum()
}
