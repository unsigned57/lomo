use boltffi_binding::{ConstantDecl, ConstantValueDecl, DefaultValue, Native, TypeRef};

use crate::{
    core::{Error, Result},
    target::python::{
        name_style::Name,
        syntax::{CallExpression, Expression, Identifier, Literal, TypeAnnotation},
    },
};

use super::{Package, callable::ReturnStub, type_hint::TypeHint};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ConstantStub {
    pub python_name: Identifier,
    pub annotation: TypeAnnotation,
    pub expression: Expression,
    uses_wire_helpers: bool,
}

impl ConstantStub {
    pub fn from_declaration(constant: &ConstantDecl<Native>, package: &Package) -> Result<Self> {
        match constant.value() {
            ConstantValueDecl::Inline { ty, value, .. } => {
                Self::from_inline(constant, ty, value, package)
            }
            ConstantValueDecl::Accessor { callable, .. } => {
                let returned = ReturnStub::from_plan(callable.returns().plan(), package)?;
                let native_call = Expression::call(CallExpression::new(Expression::attribute(
                    Expression::identifier(Identifier::parse("_native")?),
                    Name::new(constant.name()).function()?,
                )));
                let expression = returned.expression(native_call)?;
                let uses_wire_helpers = returned.uses_wire_helpers();
                Ok(Self {
                    python_name: Name::new(constant.name()).function()?,
                    annotation: returned.into_annotation(),
                    expression,
                    uses_wire_helpers,
                })
            }
            _ => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "unknown constant value package",
            }),
        }
    }

    pub fn uses_wire_helpers(&self) -> bool {
        self.uses_wire_helpers
    }

    pub fn top_level_name(&self) -> (String, String) {
        (
            self.python_name.to_string(),
            format!("constant `{}`", self.python_name),
        )
    }

    fn from_inline(
        constant: &ConstantDecl<Native>,
        ty: &TypeRef,
        value: &DefaultValue,
        package: &Package,
    ) -> Result<Self> {
        Ok(Self {
            python_name: Name::new(constant.name()).function()?,
            annotation: TypeHint::from_type_ref(ty, package)?.into_annotation(),
            expression: DefaultExpression::new(value, package)?.into_expression(),
            uses_wire_helpers: false,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DefaultExpression {
    expression: Expression,
}

impl DefaultExpression {
    pub fn new(value: &DefaultValue, package: &Package) -> Result<Self> {
        Ok(Self {
            expression: match value {
                DefaultValue::Bool(value) => Expression::literal(Literal::bool(*value)),
                DefaultValue::Integer(value) => Expression::literal(Literal::integer(value.get())),
                DefaultValue::Float(value) => Literal::float(value.to_f64()),
                DefaultValue::String(value) => Expression::literal(Literal::string(value)),
                DefaultValue::EnumVariant {
                    enum_name,
                    variant_name,
                } => package.enum_variant_expression(enum_name, variant_name)?,
                DefaultValue::Null => Expression::literal(Literal::none()),
                _ => {
                    return Err(Error::UnsupportedTarget {
                        target: "python",
                        shape: "unknown constant literal",
                    });
                }
            },
        })
    }

    pub fn into_expression(self) -> Expression {
        self.expression
    }
}
