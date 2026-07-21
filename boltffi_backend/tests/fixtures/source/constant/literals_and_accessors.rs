#[repr(u8)]
#[data]
pub enum Mode {
    Fast = 1,
    Slow = 2,
}

#[export]
pub const ENABLED: bool = true;

#[export]
pub const LIMIT: u32 = 1024;

#[export]
pub const HALF: f64 = 0.5;

#[export]
pub const GREETING: &'static str = "hello";

#[export]
pub const DEFAULT_MODE: Mode = Mode::Fast;

#[export]
pub const MAGIC: &'static [u8] = b"ffi";
