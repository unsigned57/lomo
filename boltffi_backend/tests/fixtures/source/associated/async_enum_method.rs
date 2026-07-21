#[repr(u8)]
#[data]
pub enum Mode {
    Fast = 1,
    Slow = 2,
}

#[data(impl)]
impl Mode {
    pub async fn compute(&self) -> u32 {
        7
    }
}
