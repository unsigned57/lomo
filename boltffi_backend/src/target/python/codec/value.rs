use boltffi_binding::{BinderId, FieldKey, ValueRef, ValueRoot};

use crate::{
    core::{Error, Result},
    target::python::{
        name_style::Name,
        syntax::{Expression, Identifier, Literal},
    },
};

pub struct ValueExpression {
    value: ValueRef,
    self_position_access: SelfPositionAccess,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SelfPositionAccess {
    Attribute,
    Subscript,
}

impl ValueExpression {
    pub fn with_self_position_access(
        value: &ValueRef,
        self_position_access: SelfPositionAccess,
    ) -> Self {
        Self {
            value: value.clone(),
            self_position_access,
        }
    }

    pub fn root(value: &ValueRef) -> Result<Expression> {
        match value.root() {
            ValueRoot::SelfValue => Identifier::parse("self").map(Expression::identifier),
            ValueRoot::Named(name) | ValueRoot::Local(name) => {
                Name::new(name).function().map(Expression::identifier)
            }
            ValueRoot::Binder(binder) => Ok(Expression::identifier(Self::binder(*binder)?)),
            _ => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "unknown codec value root",
            }),
        }
    }

    pub fn binder(binder: BinderId) -> Result<Identifier> {
        Identifier::parse(format!("__boltffi_value_{}", binder.raw()))
    }

    pub fn render(self) -> Result<Expression> {
        let root = Self::root(&self.value)?;
        let mut fields = self.value.path().iter();
        let root = match (self.value.root(), self.self_position_access, fields.next()) {
            (ValueRoot::SelfValue, SelfPositionAccess::Attribute, Some(field)) => {
                Self::self_field(root, field)?
            }
            (_, _, Some(field)) => Self::field(root, field)?,
            (_, _, None) => root,
        };
        fields.try_fold(root, Self::field)
    }

    pub fn field(expression: Expression, field: &FieldKey) -> Result<Expression> {
        Ok(match field {
            FieldKey::Named(name) => Expression::attribute(expression, Name::new(name).function()?),
            FieldKey::Position(position) => Expression::subscript(
                expression,
                Expression::literal(Literal::integer(i128::from(*position))),
            ),
            _ => {
                return Err(Error::UnsupportedTarget {
                    target: "python",
                    shape: "unknown codec value field",
                });
            }
        })
    }

    fn self_field(expression: Expression, field: &FieldKey) -> Result<Expression> {
        Ok(match field {
            FieldKey::Named(name) => Expression::attribute(expression, Name::new(name).function()?),
            FieldKey::Position(position) => {
                Expression::attribute(expression, Name::position_field(*position)?)
            }
            _ => {
                return Err(Error::UnsupportedTarget {
                    target: "python",
                    shape: "unknown codec value field",
                });
            }
        })
    }
}
