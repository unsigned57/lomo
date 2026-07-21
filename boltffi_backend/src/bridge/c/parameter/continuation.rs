use crate::core::{Error, Result};

use super::super::{C_BRIDGE_CONTRACT, Identifier};
use super::{Parameter, ParameterIndex};

/// C ABI parameters that carry one poll continuation.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ContinuationParameter {
    name: Identifier,
    data: ParameterIndex,
    callback: ParameterIndex,
}

impl ContinuationParameter {
    /// Returns the source continuation name.
    pub fn name(&self) -> &str {
        self.name.as_str()
    }

    /// Returns the callback data parameter position.
    pub const fn data(&self) -> ParameterIndex {
        self.data
    }

    /// Returns the callback function pointer parameter position.
    pub const fn callback(&self) -> ParameterIndex {
        self.callback
    }

    pub(in crate::bridge::c::parameter) fn from_params(
        params: &[Parameter],
        data: usize,
        name: &Identifier,
    ) -> Result<Self> {
        let callback = data + 1;
        let callback_role = params
            .get(callback)
            .map(|parameter| &parameter.role)
            .ok_or(Error::BrokenBridgeContract {
                bridge: C_BRIDGE_CONTRACT,
                invariant: "continuation parameter group is missing callback parameter",
            })?;

        if !callback_role.is_continuation_callback(name) {
            return Err(Error::BrokenBridgeContract {
                bridge: C_BRIDGE_CONTRACT,
                invariant: "continuation parameter group has mismatched callback parameter",
            });
        }

        Ok(Self {
            name: name.clone(),
            data: ParameterIndex::new(data),
            callback: ParameterIndex::new(callback),
        })
    }
}
