use boltffi_binding::{BinderId, FieldKey, ValueRef, ValueRoot};

use crate::{
    core::Result,
    target::kotlin::{
        KotlinHost,
        name_style::Name,
        syntax::{Expression, Identifier},
    },
};

pub struct ValueExpression {
    value: ValueRef,
    current: Expression,
}

impl ValueExpression {
    pub fn new(value: &ValueRef) -> Result<Self> {
        Ok(Self {
            value: value.clone(),
            current: Expression::identifier(Identifier::parse("value")?),
        })
    }

    pub fn binder(binder: BinderId) -> Result<Identifier> {
        Identifier::parse(format!("__boltffi_value_{}", binder.raw()))
    }

    pub fn current(mut self, current: Expression) -> Self {
        self.current = current;
        self
    }

    pub fn render(self) -> Result<Expression> {
        let root = match self.value.root() {
            ValueRoot::SelfValue => self.current,
            ValueRoot::Named(name) | ValueRoot::Local(name) => {
                Name::new(name).parameter().map(Expression::identifier)?
            }
            ValueRoot::Binder(binder) => Expression::identifier(Self::binder(*binder)?),
            _ => {
                return Err(KotlinHost::unsupported("unknown codec value root"));
            }
        };
        self.value.path().iter().try_fold(root, Self::field)
    }

    pub fn field(expression: Expression, field: &FieldKey) -> Result<Expression> {
        match field {
            FieldKey::Named(name) => Name::new(name)
                .parameter()
                .map(|field| Expression::property(expression, field)),
            FieldKey::Position(position) => Identifier::parse(format!("field{position}"))
                .map(|field| Expression::property(expression, field)),
            _ => Err(KotlinHost::unsupported("unknown codec value field")),
        }
    }
}
