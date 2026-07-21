#[export]
pub fn echo_values(values: Vec<i32>) -> Vec<i32> {
    values
}

#[export]
pub fn echo_words(values: Vec<u32>) -> Vec<u32> {
    values
}

#[export]
pub fn make_ratios() -> Vec<f64> {
    vec![1.0, 2.5, 4.0]
}
