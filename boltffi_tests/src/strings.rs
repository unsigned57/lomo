use boltffi::*;

#[export]
pub fn shout(text: String) -> String {
    text.to_uppercase()
}

#[export]
pub fn borrowed_len(text: &str) -> u32 {
    text.len() as u32
}

#[export]
pub fn rewrite(text: &mut String, suffix: String) {
    text.push_str(&suffix);
}
