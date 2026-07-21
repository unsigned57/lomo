use boltffi_binding::{
    DirectVectorElementType, DirectVectorPrimitive, Native, Primitive, RecordId,
};

use crate::{
    core::{RenderContext, Result},
    target::kotlin::{
        KotlinHost,
        name_style::Name,
        primitive::KotlinPrimitive,
        render::record::Record,
        syntax::{ArgumentList, Expression, Identifier, Literal, Statement, TypeName},
    },
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DirectVector {
    ty: TypeName,
    codec: DirectVectorCodec,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DecodedArgument {
    jvm_ty: TypeName,
    setup: Vec<Statement>,
    call_argument: Expression,
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum DirectVectorCodec {
    Primitive {
        decoder: Identifier,
        encoder: Identifier,
        native_argument: Option<Identifier>,
    },
    Record {
        name: TypeName,
        size: u64,
    },
}

impl DirectVector {
    pub fn from_element(
        element: &DirectVectorElementType,
        context: &RenderContext<Native>,
    ) -> Result<Self> {
        match element {
            DirectVectorElementType::Primitive(primitive) => Self::from_primitive(*primitive),
            DirectVectorElementType::Record(record) => Self::from_record(*record, context),
            _ => Err(KotlinHost::unsupported("unknown direct-vector type")),
        }
    }

    pub fn ty(&self) -> &TypeName {
        &self.ty
    }

    pub fn decoded_argument(
        &self,
        source_name: &Name,
        name: Identifier,
    ) -> Result<DecodedArgument> {
        match &self.codec {
            DirectVectorCodec::Primitive { .. } => Ok(DecodedArgument {
                jvm_ty: self.ty.clone(),
                setup: Vec::new(),
                call_argument: Expression::identifier(name),
            }),
            DirectVectorCodec::Record { .. } => {
                let value = source_name.generated("values")?;
                Ok(DecodedArgument {
                    jvm_ty: TypeName::byte_array(false),
                    setup: vec![Statement::value(
                        value.clone(),
                        self.decode_byte_array(Expression::identifier(name))?,
                    )],
                    call_argument: Expression::identifier(value),
                })
            }
        }
    }

    pub fn native_argument(&self, value: Expression) -> Result<Expression> {
        match &self.codec {
            DirectVectorCodec::Primitive {
                native_argument, ..
            } => Ok(native_argument
                .as_ref()
                .map_or(value.clone(), |method| value.convert(method.clone()))),
            DirectVectorCodec::Record { .. } => self.byte_array_expression(value),
        }
    }

    pub fn value_statements(&self, call: Expression) -> Result<Vec<Statement>> {
        let result = Identifier::parse("__boltffi_result")?;
        let payload = call.or_else(Expression::throw_illegal_state(Literal::string(
            "null buffer returned",
        )));
        let decoded = self.decode_byte_array(Expression::identifier(result.clone()))?;
        Ok(vec![
            Statement::value(result, payload),
            Statement::expression(decoded),
        ])
    }

    pub fn byte_array_expression(&self, value: Expression) -> Result<Expression> {
        match &self.codec {
            DirectVectorCodec::Primitive { encoder, .. } => Ok(Expression::call(
                "DirectVectorCodec",
                encoder.clone(),
                [value].into_iter().collect::<ArgumentList>(),
            )),
            DirectVectorCodec::Record { size, .. } => {
                let item = Identifier::parse("item")?;
                let buffer = Identifier::parse("buffer")?;
                let offset = Identifier::parse("offset")?;
                Ok(Expression::call(
                    "DirectVectorCodec",
                    Identifier::parse("writeRecordList")?,
                    [
                        value,
                        Expression::integer(*size),
                        Expression::lambda_statement(
                            vec![item.clone(), buffer.clone(), offset.clone()],
                            Statement::expression(Expression::call(
                                Expression::identifier(item),
                                Identifier::parse("writeTo")?,
                                [
                                    Expression::identifier(buffer),
                                    Expression::identifier(offset),
                                ]
                                .into_iter()
                                .collect::<ArgumentList>(),
                            )),
                        ),
                    ]
                    .into_iter()
                    .collect::<ArgumentList>(),
                ))
            }
        }
    }

    fn from_primitive(primitive: DirectVectorPrimitive) -> Result<Self> {
        let primitive = primitive.primitive();
        Ok(Self {
            ty: KotlinPrimitive::new(primitive).direct_vector_type()?,
            codec: DirectVectorCodec::Primitive {
                decoder: Identifier::parse(Self::decoder_name(primitive)?)?,
                encoder: Identifier::parse(Self::encoder_name(primitive)?)?,
                native_argument: Self::native_argument_name(primitive)
                    .map(Identifier::parse)
                    .transpose()?,
            },
        })
    }

    fn from_record(record: RecordId, context: &RenderContext<Native>) -> Result<Self> {
        let name = Record::type_name_from_id(record, context)?;
        Ok(Self {
            ty: TypeName::list(name.clone()),
            codec: DirectVectorCodec::Record {
                name,
                size: Record::direct_size_from_id(record, context)?,
            },
        })
    }

    pub fn decode_byte_array(&self, value: Expression) -> Result<Expression> {
        match &self.codec {
            DirectVectorCodec::Primitive { decoder, .. } => Ok(Expression::call(
                "DirectVectorCodec",
                decoder.clone(),
                [value].into_iter().collect::<ArgumentList>(),
            )),
            DirectVectorCodec::Record { name, size } => {
                let buffer = Identifier::parse("buffer")?;
                let offset = Identifier::parse("offset")?;
                Ok(Expression::call(
                    "DirectVectorCodec",
                    Identifier::parse("readRecordList")?,
                    [
                        value,
                        Expression::integer(*size),
                        Expression::lambda_expression(
                            vec![buffer.clone(), offset.clone()],
                            Expression::call(
                                name.clone(),
                                Identifier::parse("fromBuffer")?,
                                [
                                    Expression::identifier(buffer),
                                    Expression::identifier(offset),
                                ]
                                .into_iter()
                                .collect::<ArgumentList>(),
                            ),
                        ),
                    ]
                    .into_iter()
                    .collect::<ArgumentList>(),
                ))
            }
        }
    }

    fn decoder_name(primitive: Primitive) -> Result<&'static str> {
        match primitive {
            Primitive::Bool => Ok("readBooleanArray"),
            Primitive::I8 => Ok("readByteArray"),
            Primitive::I16 => Ok("readShortArray"),
            Primitive::I32 => Ok("readIntArray"),
            Primitive::I64 | Primitive::ISize => Ok("readLongArray"),
            Primitive::F32 => Ok("readFloatArray"),
            Primitive::F64 => Ok("readDoubleArray"),
            Primitive::U8 => Ok("readByteArray"),
            Primitive::U16 => Ok("readUShortArray"),
            Primitive::U32 => Ok("readUIntArray"),
            Primitive::U64 | Primitive::USize => Ok("readULongArray"),
            _ => Err(KotlinHost::unsupported("unknown direct-vector primitive")),
        }
    }

    fn encoder_name(primitive: Primitive) -> Result<&'static str> {
        match primitive {
            Primitive::Bool => Ok("writeBooleanArray"),
            Primitive::I8 => Ok("writeByteArray"),
            Primitive::I16 => Ok("writeShortArray"),
            Primitive::U16 => Ok("writeUShortArray"),
            Primitive::I32 => Ok("writeIntArray"),
            Primitive::U32 => Ok("writeUIntArray"),
            Primitive::I64 | Primitive::ISize => Ok("writeLongArray"),
            Primitive::U64 | Primitive::USize => Ok("writeULongArray"),
            Primitive::F32 => Ok("writeFloatArray"),
            Primitive::F64 => Ok("writeDoubleArray"),
            Primitive::U8 => Ok("writeByteArray"),
            _ => Err(KotlinHost::unsupported("unknown direct-vector primitive")),
        }
    }

    fn native_argument_name(primitive: Primitive) -> Option<&'static str> {
        match primitive {
            Primitive::U16 => Some("asShortArray"),
            Primitive::U32 => Some("asIntArray"),
            Primitive::U64 | Primitive::USize => Some("asLongArray"),
            _ => None,
        }
    }
}

impl DecodedArgument {
    pub fn jvm_ty(&self) -> &TypeName {
        &self.jvm_ty
    }

    pub fn setup(&self) -> &[Statement] {
        &self.setup
    }

    pub fn call_argument(&self) -> &Expression {
        &self.call_argument
    }
}
