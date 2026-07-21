#[export]
pub fn add(left: i32, right: i32) -> i32 {
    left + right
}

#[export]
pub fn enabled(flag: bool) -> bool {
    flag
}

#[export]
pub fn refresh() {}

#[export]
pub fn echo_bytes(bytes: Vec<u8>) -> Vec<u8> {
    bytes
}
