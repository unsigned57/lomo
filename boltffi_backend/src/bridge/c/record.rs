use boltffi_binding::{DirectRecordDecl, Native};

use crate::core::Result;

use super::{Identifier, Type, name, names::Names};

/// A C record typedef.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct Record {
    name: Identifier,
    fields: Vec<Field>,
}

/// A C field declaration.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct Field {
    name: Identifier,
    ty: Type,
}

impl Record {
    /// Returns the C typedef name.
    pub fn name(&self) -> &str {
        self.name.as_str()
    }

    /// Returns the C fields in declaration order.
    pub fn fields(&self) -> &[Field] {
        &self.fields
    }
}

impl Record {
    /// Creates a C typedef for a direct source record.
    pub fn direct(record: &DirectRecordDecl<Native>, names: &Names) -> Result<Self> {
        let name = names.record(record.id())?;
        let fields = record
            .fields()
            .iter()
            .map(|field| {
                Field::new(
                    name::Field::new(field.key()).spelling()?,
                    Type::primitive(field.ty().primitive())?,
                )
            })
            .collect::<Result<Vec<_>>>()?;
        Ok(Self { name, fields })
    }

    /// Creates a C record typedef from its generated name and fields.
    pub fn new(name: Identifier, fields: Vec<Field>) -> Self {
        Self { name, fields }
    }
}

impl Field {
    /// Returns the field name.
    pub fn name(&self) -> &str {
        self.name.as_str()
    }

    /// Returns the field type.
    pub fn ty(&self) -> &Type {
        &self.ty
    }
}

impl Field {
    /// Creates a C field declaration.
    pub fn new(name: impl Into<String>, ty: Type) -> Result<Self> {
        Ok(Self {
            name: Identifier::escape(name)?,
            ty,
        })
    }

    /// Creates a C field from a validated identifier and type.
    pub fn from_parts(name: Identifier, ty: Type) -> Self {
        Self { name, ty }
    }
}
