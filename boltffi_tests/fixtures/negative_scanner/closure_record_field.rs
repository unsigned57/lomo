#[data]
pub struct Handler {
    pub callback: Box<dyn Fn(u32)>,
}
