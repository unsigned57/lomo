pub struct Engine;

#[export(single_threaded)]
impl Engine {
    pub fn new() -> Self {
        Self
    }
}

#[export]
pub fn install(callback: impl Fn() -> Engine) {}
