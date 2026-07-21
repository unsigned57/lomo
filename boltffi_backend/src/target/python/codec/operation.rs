use boltffi_binding::{DirectValueType, FieldKey, IntrinsicOp, OpRender, ValueRef, ValueRoot};

use crate::{
    core::{Error, Result},
    target::python::{
        codec::value::{SelfPositionAccess, ValueExpression},
        cpython::render::primitive,
        syntax::{CallExpression, Expression, Identifier, Literal},
    },
};

pub struct Operation {
    current_value: ValueRef,
    self_position_access: SelfPositionAccess,
}

impl Operation {
    pub fn new(current_value: &ValueRef, self_position_access: SelfPositionAccess) -> Self {
        Self {
            current_value: current_value.clone(),
            self_position_access,
        }
    }

    fn value_ref(&self, value: &ValueRef) -> ValueRef {
        match value.root() {
            ValueRoot::SelfValue => self.current_value.clone(),
            _ => value.clone(),
        }
    }

    fn binary(
        left: Result<Expression>,
        right: Result<Expression>,
        operator: &'static str,
    ) -> Result<Expression> {
        Ok(Expression::binary(left?, operator, right?))
    }

    fn single_argument(args: Vec<Result<Expression>>) -> Result<Expression> {
        let mut args = args.into_iter().collect::<Result<Vec<_>>>()?;
        match args.len() {
            1 => Ok(args.remove(0)),
            _ => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "python operation with invalid arity",
            }),
        }
    }
}

impl OpRender for Operation {
    type Expr = Result<Expression>;

    fn value(&mut self, value: &ValueRef) -> Self::Expr {
        ValueExpression::with_self_position_access(
            &self.value_ref(value),
            self.self_position_access,
        )
        .render()
    }

    fn byte_count(&mut self, bytes: u64) -> Self::Expr {
        Ok(Expression::literal(Literal::integer(i128::from(bytes))))
    }

    fn integer(&mut self, value: i128) -> Self::Expr {
        Ok(Expression::literal(Literal::integer(value)))
    }

    fn add(&mut self, left: Self::Expr, right: Self::Expr) -> Self::Expr {
        Self::binary(left, right, "+")
    }

    fn mul(&mut self, left: Self::Expr, right: Self::Expr) -> Self::Expr {
        Self::binary(left, right, "*")
    }

    fn eq(&mut self, left: Self::Expr, right: Self::Expr) -> Self::Expr {
        Self::binary(left, right, "==")
    }

    fn field(&mut self, base: Self::Expr, field: &FieldKey) -> Self::Expr {
        ValueExpression::field(base?, field)
    }

    fn intrinsic(&mut self, intrinsic: IntrinsicOp, args: Vec<Self::Expr>) -> Self::Expr {
        let value = Self::single_argument(args)?;
        match intrinsic {
            IntrinsicOp::Utf8ByteCount => {
                let string = Expression::call(
                    CallExpression::new(Expression::identifier(Identifier::parse("str")?))
                        .positional(value),
                );
                let bytes = Expression::call(
                    CallExpression::new(Expression::attribute(
                        string,
                        Identifier::parse("encode")?,
                    ))
                    .positional(Expression::literal(Literal::string("utf-8"))),
                );
                Ok(Expression::call(
                    CallExpression::new(Expression::identifier(Identifier::parse("len")?))
                        .positional(bytes),
                ))
            }
            IntrinsicOp::SequenceLen => Ok(Expression::call(
                CallExpression::new(Expression::identifier(Identifier::parse("len")?))
                    .positional(value),
            )),
            IntrinsicOp::WireSize => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "python wire-size operation",
            }),
            _ => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "unknown python operation",
            }),
        }
    }

    fn size_of(&mut self, ty: &DirectValueType) -> Self::Expr {
        match ty {
            DirectValueType::Primitive(primitive) => primitive::Runtime::new(*primitive)
                .wire_size()
                .map(|size| Expression::literal(Literal::integer(size as i128))),
            DirectValueType::Record(_) | DirectValueType::Enum(_) => {
                Err(Error::UnsupportedTarget {
                    target: "python",
                    shape: "python type-size operation",
                })
            }
            _ => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "unknown python type-size operation",
            }),
        }
    }
}
