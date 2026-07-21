use askama::Template;

use super::plan::JniModule;
use super::templates::JniGlueTemplate;

pub struct JniEmitter;

impl JniEmitter {
    pub fn emit(module: &JniModule) -> String {
        JniGlueTemplate::new(module).render().unwrap()
    }
}
