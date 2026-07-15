#![deny(unsafe_code)]

mod model;
mod planner;
mod protocol;
mod validation;

pub use model::{
    Action, Backend, Direction, LocalSnapshot, MAGIC, MAX_ITEMS, MAX_PAYLOAD_BYTES,
    MAX_STRING_BYTES, MetadataSnapshot, PROTOCOL_VERSION, Plan, ProtocolError, Reason,
    RemoteAbsenceVerification, RemoteSnapshot, Request,
};
pub use planner::plan;
pub use protocol::{decode_plan, encode_request, plan_envelope};
