#[derive(Debug, Clone)]
pub struct DartClass {
    pub name: String,
    pub create_symbol: String,
    pub free_symbol: String,
    pub constructors: Vec<super::DartConstructor>,
    pub methods: Vec<super::DartFunction>,
    pub streams: Vec<super::DartStream>,
}
