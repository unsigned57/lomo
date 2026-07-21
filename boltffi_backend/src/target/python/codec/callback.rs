use boltffi_binding::{ReadPlan, WritePlan};

use crate::{
    bridge::c::{ArgumentList, Expression, Identifier},
    core::Result,
    target::python::codec::{AdapterKey, Marshaling},
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BorrowedPayload {
    expression: Expression,
}

impl BorrowedPayload {
    pub fn read(plan: &ReadPlan, pointer: Identifier, length: Identifier) -> Result<Self> {
        Ok(Self {
            expression: Expression::call(
                AdapterKey::read(plan).c_decoder()?,
                ArgumentList::from_iter([
                    Expression::identifier(pointer),
                    Expression::identifier(length),
                ]),
            ),
        })
    }

    pub fn expression(self) -> Expression {
        self.expression
    }

    pub fn marshaling(&self) -> Marshaling {
        Marshaling::none()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OwnedPayload {
    parser: Identifier,
}

impl OwnedPayload {
    pub fn write(plan: &WritePlan) -> Result<Self> {
        Ok(Self {
            parser: AdapterKey::write(plan).c_encoder()?,
        })
    }

    pub fn parser(&self) -> &Identifier {
        &self.parser
    }

    pub fn marshaling(&self) -> Marshaling {
        Marshaling::none()
    }
}
