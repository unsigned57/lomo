#[export]
pub trait Collector {
    fn on_values(&self, values: Vec<i32>);
}
