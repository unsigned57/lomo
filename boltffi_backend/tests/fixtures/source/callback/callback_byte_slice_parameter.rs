#[export]
pub trait Listener {
    fn on_name(&self, name: String);
}
