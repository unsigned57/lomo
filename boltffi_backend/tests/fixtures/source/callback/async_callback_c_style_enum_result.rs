#[repr(u8)]
#[data]
pub enum Mode {
    Fast = 1,
    Slow = 2,
}

#[export]
pub trait Listener {
    async fn mode(&self) -> Mode;
}
