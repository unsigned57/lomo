use crate::{
    bridge::{
        c,
        jni::{CallbackCParameter, SuccessOutArgument},
    },
    core::Result,
};

use super::super::{CallbackArgument, CallbackArgumentKind};

pub fn from_parameter(parameter: &c::Parameter) -> Result<CallbackArgument> {
    Ok(CallbackArgument {
        kind: CallbackArgumentKind::SuccessOut {
            parameter: CallbackCParameter::from_parameter(parameter)?,
            argument: SuccessOutArgument::from_parameter(parameter)?,
        },
    })
}
