use crate::bridge::{
    c::Identifier,
    python_cext::{ExtensionMethod, MethodName},
};

pub struct Entry {
    pub python_name: MethodName,
    pub c_function: Identifier,
    pub flags: &'static str,
}

impl Entry {
    pub fn from_method(method: &ExtensionMethod) -> Self {
        Self {
            python_name: method.python_name().clone(),
            c_function: method.c_function().clone(),
            flags: method.flags().as_c_macro(),
        }
    }
}
