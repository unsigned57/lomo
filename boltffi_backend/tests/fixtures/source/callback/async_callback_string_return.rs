#[export]
pub trait Listener {
    async fn load(&self, key: u32) -> String;
}
