#[export]
pub fn install(callback: extern "C" fn(u32)) {
    let _ = callback;
}
