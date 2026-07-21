#[export]
pub trait Listener {
    fn process(&self, values: Vec<i32>) -> Vec<i32>;
}
