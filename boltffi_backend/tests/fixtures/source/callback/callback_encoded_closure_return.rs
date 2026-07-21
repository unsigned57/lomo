#[export]
pub trait Listener {
    fn make_handler(&self) -> impl Fn(String) -> String;
}
