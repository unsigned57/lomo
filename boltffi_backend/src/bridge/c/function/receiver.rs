use boltffi_binding::Receive;

use crate::core::Result;

use super::{Parameter, Type};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReceiverAbi {
    input: Vec<Parameter>,
    writeback: Option<Parameter>,
}

impl ReceiverAbi {
    pub fn plain(params: impl IntoIterator<Item = Parameter>) -> Self {
        Self {
            input: params.into_iter().collect(),
            writeback: None,
        }
    }

    pub fn direct(name: &str, ty: Type) -> Result<Self> {
        Ok(Self {
            input: vec![Parameter::new(name, ty.clone())?],
            writeback: Some(Parameter::new(
                format!("{name}_out"),
                Type::MutPointer(Box::new(ty)),
            )?),
        })
    }

    pub fn encoded(name: &str) -> Result<Self> {
        Ok(Self {
            input: vec![
                Parameter::byte_pointer(name)?,
                Parameter::byte_length(name)?,
            ],
            writeback: Some(Parameter::encoded_writeback(name)?),
        })
    }

    pub fn parameters(&self, receive: Receive) -> Vec<Parameter> {
        self.input
            .iter()
            .cloned()
            .chain(
                matches!(receive, Receive::ByMutRef)
                    .then(|| self.writeback.clone())
                    .flatten(),
            )
            .collect()
    }
}
