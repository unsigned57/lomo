#[error]
pub enum MathError {
    InvalidInput,
}

#[export]
pub fn apply(callback: impl Fn(i32) -> Result<i32, MathError>, value: i32) -> Result<i32, MathError> {
    callback(value)
}
