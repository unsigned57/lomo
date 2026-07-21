#[repr(u8)]
#[data]
pub enum Mode {
    Fast = 1,
    Slow = 2,
}

#[data(impl)]
impl Mode {
    pub fn default() -> Self {
        Self::Fast
    }

    pub fn code(&self) -> u8 {
        0
    }
}
