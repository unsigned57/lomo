#[export]
pub trait Child {
    fn on_value(&self, value: u32) -> u32;
}

#[export]
pub trait Sibling {
    fn on_name(&self, name: &str);
}

#[export]
pub trait Listener {
    async fn child(&self) -> Box<dyn Child>;
    async fn sibling(&self) -> Box<dyn Sibling>;
}
