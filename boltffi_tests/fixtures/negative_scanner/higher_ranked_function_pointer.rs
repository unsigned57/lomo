#[export]
pub fn install(callback: for<'a> fn(&'a str)) {
    let _ = callback;
}
