use boltffi_binding::Primitive;

use crate::{
    core::Result,
    target::kotlin::{
        codec::{EncodedWrite, WireBuffer},
        name_style::Name,
        primitive::KotlinPrimitive,
        syntax::{ArgumentList, Expression, Identifier, Literal, Statement, TypeName},
    },
};

pub struct ScalarOption {
    primitive: Primitive,
}

impl ScalarOption {
    pub fn new(primitive: Primitive) -> Self {
        Self { primitive }
    }

    pub fn ty(&self) -> Result<TypeName> {
        KotlinPrimitive::new(self.primitive)
            .api_type()
            .map(TypeName::nullable)
    }

    pub fn write(&self, name: &Name) -> Result<EncodedWrite> {
        self.write_value(name, Expression::identifier(name.parameter()?))
    }

    pub fn write_value(&self, name: &Name, value: Expression) -> Result<EncodedWrite> {
        let size = Expression::conditional(
            value.clone().equal(Expression::null()),
            Expression::integer(1),
            Expression::integer(1 + KotlinPrimitive::new(self.primitive).wire_size()?),
        );
        let buffer = WireBuffer::new(name)?;
        let writer = buffer.writer().clone();
        let writes = vec![Statement::expression(Expression::call(
            Expression::identifier(writer),
            self.write_method()?,
            [value].into_iter().collect::<ArgumentList>(),
        ))];
        buffer.write_statements(size, writes)
    }

    pub fn read_expression(&self, reader: Identifier) -> Result<Expression> {
        Ok(Expression::call(
            Expression::identifier(reader),
            self.read_method()?,
            ArgumentList::default(),
        ))
    }

    pub fn read_value(&self, call: Expression) -> Result<Vec<Statement>> {
        let result = Identifier::parse("__boltffi_result")?;
        let reader = Identifier::parse("__boltffi_reader")?;
        let payload = call.or_else(Expression::throw_illegal_state(Literal::string(
            "null buffer returned",
        )));
        let value = self.read_expression(reader.clone())?;
        Ok(vec![
            Statement::value(result.clone(), payload),
            Statement::value(
                reader,
                Expression::construct(
                    TypeName::new("WireReader"),
                    [Expression::identifier(result)]
                        .into_iter()
                        .collect::<ArgumentList>(),
                ),
            ),
            Statement::expression(value),
        ])
    }

    fn read_method(&self) -> Result<Identifier> {
        self.optional_method("readOptional")
    }

    fn write_method(&self) -> Result<Identifier> {
        self.optional_method("writeOptional")
    }

    fn optional_method(&self, prefix: &'static str) -> Result<Identifier> {
        KotlinPrimitive::new(self.primitive)
            .wire_method_suffix()
            .and_then(|suffix| Identifier::parse(format!("{prefix}{suffix}")))
    }
}
