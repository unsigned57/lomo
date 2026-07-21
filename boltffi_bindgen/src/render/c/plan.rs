pub struct CField {
    pub name: String,
    pub c_type: String,
}

pub struct CEnumVariant<'a> {
    pub name: &'a str,
    pub discriminant: i128,
}

pub struct CCallbackMethod {
    pub field_name: String,
    pub params: String,
}
