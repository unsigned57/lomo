use serde::{Deserialize, Serialize};

use super::class::Constructor;
use super::layout::{CLayout, Size, StructLayout};
use super::method::Method;
use super::types::{Deprecation, Type};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Record {
    pub name: String,
    #[serde(default = "default_true")]
    pub is_repr_c: bool,
    #[serde(default)]
    pub is_error: bool,
    pub fields: Vec<RecordField>,
    #[serde(default)]
    pub constructors: Vec<Constructor>,
    #[serde(default)]
    pub methods: Vec<Method>,
    pub doc: Option<String>,
    pub deprecated: Option<Deprecation>,
}

fn default_true() -> bool {
    true
}

impl Record {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            is_repr_c: true,
            is_error: false,
            fields: Vec::new(),
            constructors: Vec::new(),
            methods: Vec::new(),
            doc: None,
            deprecated: None,
        }
    }

    pub fn with_repr_c(mut self, is_repr_c: bool) -> Self {
        self.is_repr_c = is_repr_c;
        self
    }

    pub fn as_error(mut self) -> Self {
        self.is_error = true;
        self
    }

    pub fn with_field(mut self, field: RecordField) -> Self {
        self.fields.push(field);
        self
    }

    pub fn with_constructor(mut self, constructor: Constructor) -> Self {
        self.constructors.push(constructor);
        self
    }

    pub fn with_method(mut self, method: Method) -> Self {
        self.methods.push(method);
        self
    }

    pub fn has_methods(&self) -> bool {
        !self.constructors.is_empty() || !self.methods.is_empty()
    }

    pub fn with_doc(mut self, doc: impl Into<String>) -> Self {
        self.doc = Some(doc.into());
        self
    }

    pub fn maybe_doc(self, doc: Option<String>) -> Self {
        match doc {
            Some(d) => self.with_doc(d),
            None => self,
        }
    }

    pub fn with_deprecated(mut self, deprecation: Deprecation) -> Self {
        self.deprecated = Some(deprecation);
        self
    }

    pub fn is_deprecated(&self) -> bool {
        self.deprecated.is_some()
    }

    pub fn field_count(&self) -> usize {
        self.fields.len()
    }

    pub fn is_blittable(&self) -> bool {
        self.fields
            .iter()
            .all(|field| field.field_type.is_primitive())
    }

    pub fn layout(&self) -> StructLayout {
        StructLayout::from_layouts(self.fields.iter().map(|field| field.field_type.c_layout()))
    }

    pub fn struct_size(&self) -> Size {
        self.layout().total_size()
    }

    pub fn field_offsets(&self) -> Vec<usize> {
        self.layout()
            .offsets()
            .map(|offset| offset.as_usize())
            .collect()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecordField {
    pub name: String,
    pub field_type: Type,
    pub doc: Option<String>,
    pub default_value: Option<String>,
}

impl RecordField {
    pub fn new(name: impl Into<String>, field_type: Type) -> Self {
        Self {
            name: name.into(),
            field_type,
            doc: None,
            default_value: None,
        }
    }

    pub fn with_default(mut self, value: impl Into<String>) -> Self {
        self.default_value = Some(value.into());
        self
    }

    pub fn with_doc(mut self, doc: impl Into<String>) -> Self {
        self.doc = Some(doc.into());
        self
    }
}
