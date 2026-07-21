use crate::{bridge::c, core::Result};

use super::super::ClosureSuccessOutArgument;

pub fn from_parameter(parameter: &c::Parameter) -> Result<ClosureSuccessOutArgument> {
    ClosureSuccessOutArgument::from_parameter(parameter)
}
