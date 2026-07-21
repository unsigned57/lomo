#[export]
pub fn widen(byte: u8, short: u16, word: u32, wide: u64) -> u32 {
    byte as u32 + short as u32 + word + wide as u32
}
