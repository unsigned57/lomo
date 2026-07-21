#[export]
pub fn echo_string(message: String) -> String {
    message
}

#[export]
pub fn echo_str(message: &str) -> String {
    message.to_owned()
}
