#[repr(u8)]
#[data]
pub enum Mode {
    Fast = 1,
    Slow = 2,
}

#[export]
pub fn install(callback: impl Fn() -> Mode) {}
