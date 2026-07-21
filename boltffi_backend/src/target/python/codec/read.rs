use boltffi_binding::{
    BuiltinType, CallbackId, ClassId, CodecRead, CustomTypeId, ElementCount, EnumId, MapKind, Op,
    Primitive, RecordId,
};

use crate::{
    core::{Error, Result},
    target::python::{
        cpython::render::primitive,
        render::Package,
        syntax::{CallExpression, Expression, Identifier},
    },
};

pub struct Reader<'package> {
    package: &'package Package<'package>,
}

impl<'package> Reader<'package> {
    pub fn new(package: &'package Package<'package>) -> Self {
        Self { package }
    }

    pub fn sequence_expression(&self, element: Expression) -> Result<Expression> {
        Ok(Expression::call(
            CallExpression::new(Expression::attribute(
                Expression::identifier(Identifier::parse("reader")?),
                Identifier::parse("sequence")?,
            ))
            .positional(Expression::no_arg_lambda(element)),
        ))
    }

    fn reader_call(method: Identifier) -> Result<Expression> {
        Ok(Expression::call(CallExpression::new(
            Expression::attribute(Expression::identifier(Identifier::parse("reader")?), method),
        )))
    }
}

impl<'package> CodecRead for Reader<'package> {
    type Expr = Result<Expression>;

    fn primitive(&mut self, primitive: Primitive) -> Self::Expr {
        let stem = primitive::Runtime::new(primitive).wire_stem()?;
        Self::reader_call(Identifier::parse(stem)?)
    }

    fn string(&mut self) -> Self::Expr {
        Self::reader_call(Identifier::parse("string")?)
    }

    fn interned_string(&mut self, _static_values: &[String]) -> Self::Expr {
        unreachable!(
            "InternedString codec read reached Python renderer: host does not advertise InternedString capability"
        )
    }

    fn bytes(&mut self) -> Self::Expr {
        Self::reader_call(Identifier::parse("bytes")?)
    }

    fn direct_record(&mut self, id: RecordId) -> Self::Expr {
        self.encoded_record(id)
    }

    fn encoded_record(&mut self, id: RecordId) -> Self::Expr {
        Ok(Expression::call(
            CallExpression::new(Expression::attribute(
                Expression::identifier(self.package.record_name(id)?),
                Identifier::parse("_boltffi_from_reader")?,
            ))
            .positional(Expression::identifier(Identifier::parse("reader")?)),
        ))
    }

    fn c_style_enum(&mut self, id: EnumId) -> Self::Expr {
        let EnumCodec::CStyle(primitive) = self.package.enum_codec(id)? else {
            return Err(Error::UnsupportedTarget {
                target: "python",
                shape: "data enum reached c-style wire reader",
            });
        };
        let stem = primitive::Runtime::new(primitive).wire_stem()?;
        Ok(Expression::call(
            CallExpression::new(Expression::identifier(self.package.enum_name(id)?))
                .positional(Self::reader_call(Identifier::parse(stem)?)?),
        ))
    }

    fn data_enum(&mut self, id: EnumId) -> Self::Expr {
        let EnumCodec::Data { class_name } = self.package.enum_codec(id)? else {
            return Err(Error::UnsupportedTarget {
                target: "python",
                shape: "c-style enum reached data enum wire reader",
            });
        };
        Ok(Expression::call(
            CallExpression::new(Expression::attribute(
                Expression::identifier(class_name),
                Identifier::parse("_boltffi_from_reader")?,
            ))
            .positional(Expression::identifier(Identifier::parse("reader")?)),
        ))
    }

    fn class_handle(&mut self, id: ClassId) -> Self::Expr {
        self.package.class_name(&id)?;
        Err(Error::UnsupportedTarget {
            target: "python",
            shape: "class handle in wire reader",
        })
    }

    fn callback_handle(&mut self, _: CallbackId) -> Self::Expr {
        Err(Error::UnsupportedTarget {
            target: "python",
            shape: "callback handle in wire reader",
        })
    }

    fn custom(&mut self, id: CustomTypeId, representation: Self::Expr) -> Self::Expr {
        self.package.custom_type(id)?;
        representation
    }

    fn builtin(&mut self, kind: BuiltinType) -> Self::Expr {
        Ok(match kind {
            BuiltinType::Duration => Self::reader_call(Identifier::parse("duration")?)?,
            BuiltinType::SystemTime => Self::reader_call(Identifier::parse("system_time")?)?,
            BuiltinType::Uuid => Self::reader_call(Identifier::parse("uuid")?)?,
            BuiltinType::Url => Self::reader_call(Identifier::parse("url")?)?,
        })
    }

    fn optional(&mut self, inner: Self::Expr) -> Self::Expr {
        Ok(Expression::call(
            CallExpression::new(Expression::attribute(
                Expression::identifier(Identifier::parse("reader")?),
                Identifier::parse("optional")?,
            ))
            .positional(Expression::no_arg_lambda(inner?)),
        ))
    }

    fn sequence(&mut self, _: &Op<ElementCount>, element: Self::Expr) -> Self::Expr {
        self.sequence_expression(element?)
    }

    fn tuple(&mut self, elements: Vec<Self::Expr>) -> Self::Expr {
        elements
            .into_iter()
            .collect::<Result<Vec<_>>>()
            .map(Expression::tuple)
    }

    fn result(&mut self, ok: Self::Expr, err: Self::Expr) -> Self::Expr {
        Ok(Expression::call(
            CallExpression::new(Expression::attribute(
                Expression::identifier(Identifier::parse("reader")?),
                Identifier::parse("result")?,
            ))
            .positional(Expression::no_arg_lambda(ok?))
            .positional(Expression::no_arg_lambda(err?)),
        ))
    }

    fn map(&mut self, kind: MapKind, key: Self::Expr, value: Self::Expr) -> Self::Expr {
        let method = match kind {
            MapKind::Hash | MapKind::BTree => Identifier::parse("map")?,
        };
        Ok(Expression::call(
            CallExpression::new(Expression::attribute(
                Expression::identifier(Identifier::parse("reader")?),
                method,
            ))
            .positional(Expression::no_arg_lambda(key?))
            .positional(Expression::no_arg_lambda(value?)),
        ))
    }
}

pub enum EnumCodec {
    CStyle(Primitive),
    Data { class_name: Identifier },
}
