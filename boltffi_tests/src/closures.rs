use boltffi::*;

#[export]
pub fn apply(callback: impl Fn(u32) -> u32, seed: u32) -> u32 {
    callback(seed) + seed
}

#[export]
pub fn apply_boxed(callback: Box<dyn Fn(u32) -> u32>, seed: u32) -> u32 {
    callback(seed) * 2
}

#[export]
pub fn apply_optional(callback: Option<Box<dyn Fn(u32) -> u32>>, seed: u32) -> u32 {
    callback.map_or(seed, |call| call(seed))
}

#[export]
pub fn map_label(callback: impl Fn(String) -> String, text: String) -> String {
    callback(format!("{text}:in"))
}

#[export]
pub fn make_adder(base: u32) -> impl Fn(u32) -> u32 {
    move |value| base + value
}

#[export]
pub fn make_boxed_adder(base: u32) -> Box<dyn Fn(u32) -> u32> {
    Box::new(move |value| base + value)
}
