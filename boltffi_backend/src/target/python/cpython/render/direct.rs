use boltffi_binding::{DirectValueType, EnumId, Native, Primitive, RecordId};

use crate::{
    bridge::{
        c::{ArgumentList, Expression, Identifier, Literal, TypeFragment},
        python_cext::PythonCExtBridgeContract,
    },
    core::{Error, RenderContext, Result},
    target::python::cpython::render::{enumeration, primitive, record},
};

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct NativeSlot {
    stem: String,
    c_type: TypeFragment,
    parser: Identifier,
    boxer: Identifier,
    default_value: Expression,
    primitive: Option<primitive::Runtime>,
}

impl NativeSlot {
    pub fn from_direct_value(
        ty: &DirectValueType,
        bridge: &PythonCExtBridgeContract,
        context: &RenderContext<Native>,
    ) -> Result<Self> {
        match ty {
            DirectValueType::Primitive(primitive) => Self::from_primitive(*primitive),
            DirectValueType::Record(record) => Self::from_record_id(*record, bridge, context),
            DirectValueType::Enum(enumeration) => Self::from_enum_id(*enumeration, bridge, context),
            _ => Err(Error::UnsupportedTarget {
                target: "python",
                shape: "direct value",
            }),
        }
    }

    pub fn from_primitive(primitive: Primitive) -> Result<Self> {
        let runtime = primitive::Runtime::new(primitive);
        Ok(Self {
            stem: runtime.wire_stem()?.to_owned(),
            c_type: runtime.c_type()?,
            parser: runtime.parser()?,
            boxer: runtime.boxer()?,
            default_value: match primitive {
                Primitive::Bool => Expression::literal(Literal::bool_false()),
                Primitive::F32 => Expression::literal(Literal::f32_zero()),
                Primitive::F64 => Expression::literal(Literal::f64_zero()),
                _ => Expression::literal(Literal::integer_zero()),
            },
            primitive: Some(runtime),
        })
    }

    pub fn from_record_id(
        record: RecordId,
        bridge: &PythonCExtBridgeContract,
        context: &RenderContext<Native>,
    ) -> Result<Self> {
        let symbols = record::Symbols::from_record_id(record, bridge, context)?;
        Ok(Self {
            stem: symbols.stem().to_owned(),
            c_type: symbols.c_type()?.clone(),
            parser: symbols.parser().clone(),
            boxer: symbols.boxer().clone(),
            default_value: Expression::literal(Literal::compound_zero()),
            primitive: None,
        })
    }

    pub fn from_enum_id(
        enumeration: EnumId,
        bridge: &PythonCExtBridgeContract,
        context: &RenderContext<Native>,
    ) -> Result<Self> {
        let symbols = enumeration::Symbols::from_enum_id(enumeration, bridge, context)?;
        Ok(Self {
            stem: symbols.stem().to_owned(),
            c_type: symbols.c_type()?.clone(),
            parser: symbols.parser().clone(),
            boxer: symbols.boxer().clone(),
            default_value: Expression::literal(Literal::integer_zero()),
            primitive: None,
        })
    }

    pub fn c_type(&self) -> &TypeFragment {
        &self.c_type
    }

    pub fn stem(&self) -> &str {
        &self.stem
    }

    pub fn parser(&self) -> &Identifier {
        &self.parser
    }

    pub fn boxer(&self) -> &Identifier {
        &self.boxer
    }

    pub fn default_value(&self) -> &Expression {
        &self.default_value
    }

    pub fn primitive(&self) -> Option<primitive::Runtime> {
        self.primitive
    }

    pub fn box_expression(&self, value: Identifier) -> Expression {
        Expression::call(
            self.boxer.clone(),
            ArgumentList::from_iter([Expression::identifier(value)]),
        )
    }
}
