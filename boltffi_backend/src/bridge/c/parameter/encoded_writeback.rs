use crate::core::{Error, Result};

use super::super::{C_BRIDGE_CONTRACT, Identifier, Type};
use super::{ByteSliceParameter, Parameter, ParameterIndex};

/// C ABI parameters that carry borrowed bytes plus an encoded mutation result.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct EncodedWritebackParameter {
    bytes: ByteSliceParameter,
    output: ParameterIndex,
}

impl EncodedWritebackParameter {
    /// Returns the source parameter name shared by the input bytes and output buffer.
    pub fn name(&self) -> &str {
        self.bytes.name()
    }

    /// Returns the borrowed byte pointer parameter position.
    pub const fn pointer(&self) -> ParameterIndex {
        self.bytes.pointer()
    }

    /// Returns the borrowed byte length parameter position.
    pub const fn length(&self) -> ParameterIndex {
        self.bytes.length()
    }

    /// Returns the encoded output buffer parameter position.
    pub const fn output(&self) -> ParameterIndex {
        self.output
    }

    /// Returns the borrowed byte-slice half of this mutation group.
    pub fn bytes(&self) -> &ByteSliceParameter {
        &self.bytes
    }

    /// Creates an encoded writeback group when the parameter sequence has pointer, length, and output storage.
    pub fn from_params(
        params: &[Parameter],
        pointer: usize,
        name: &Identifier,
    ) -> Result<Option<Self>> {
        let bytes = ByteSliceParameter::from_params(params, pointer, name)?;
        let output_index = pointer + 2;
        let Some(output) = params.get(output_index) else {
            return Ok(None);
        };
        let expected_output = format!("{name}_out");
        if output.name() != expected_output {
            return Ok(None);
        }
        if !output.role.is_encoded_writeback(name) {
            return Err(Error::BrokenBridgeContract {
                bridge: C_BRIDGE_CONTRACT,
                invariant: "encoded writeback output parameter has unexpected role",
            });
        }
        if output.ty() != &Type::MutPointer(Box::new(Type::Buffer)) {
            return Err(Error::BrokenBridgeContract {
                bridge: C_BRIDGE_CONTRACT,
                invariant: "encoded writeback output parameter is not FfiBuf_u8 pointer",
            });
        }

        Ok(Some(Self {
            bytes,
            output: ParameterIndex::new(output_index),
        }))
    }
}
