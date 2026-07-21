use crate::core::{Error, Result};

use super::super::{C_BRIDGE_CONTRACT, Identifier};
use super::{Parameter, ParameterIndex};

/// C ABI parameters that carry one borrowed byte slice argument.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ByteSliceParameter {
    name: Identifier,
    pointer: ParameterIndex,
    length: ParameterIndex,
}

impl ByteSliceParameter {
    /// Returns the source parameter name.
    pub fn name(&self) -> &str {
        self.name.as_str()
    }

    /// Returns the byte pointer parameter position.
    pub const fn pointer(&self) -> ParameterIndex {
        self.pointer
    }

    /// Returns the byte length parameter position.
    pub const fn length(&self) -> ParameterIndex {
        self.length
    }

    pub(in crate::bridge::c::parameter) fn from_params(
        params: &[Parameter],
        pointer: usize,
        name: &Identifier,
    ) -> Result<Self> {
        let length = pointer + 1;
        let length_role = params.get(length).map(|parameter| &parameter.role).ok_or(
            Error::BrokenBridgeContract {
                bridge: C_BRIDGE_CONTRACT,
                invariant: "byte slice parameter group is missing length parameter",
            },
        )?;

        if !length_role.is_byte_length(name) {
            return Err(Error::BrokenBridgeContract {
                bridge: C_BRIDGE_CONTRACT,
                invariant: "byte slice parameter group has mismatched length parameter",
            });
        }

        Ok(Self {
            name: name.clone(),
            pointer: ParameterIndex::new(pointer),
            length: ParameterIndex::new(length),
        })
    }
}
