use std::collections::BTreeSet;

use crate::model::{
    Action, Backend, Direction, LocalSnapshot, MAGIC, MAX_ITEMS, MAX_PAYLOAD_BYTES,
    MAX_STRING_BYTES, MetadataSnapshot, PROTOCOL_VERSION, Plan, ProtocolError, Reason,
    RemoteAbsenceVerification, RemoteSnapshot, Request,
};
use crate::planner::plan;
use crate::validation::{validate_non_negative, validate_path, validate_request, validate_string};

/// Decodes, validates, plans, and encodes one sync v1 envelope.
///
/// # Errors
///
/// Returns [`ProtocolError`] when the input is malformed, unsupported, too large, or contains an
/// invalid domain state.
pub fn plan_envelope(input: &[u8]) -> Result<Vec<u8>, ProtocolError> {
    if input.len() > MAX_PAYLOAD_BYTES {
        return Err(ProtocolError::PayloadTooLarge);
    }
    let request = decode_request(input)?;
    let plan = plan(&request)?;
    encode_plan(&plan)
}

/// Encodes a validated sync v1 request.
///
/// # Errors
///
/// Returns [`ProtocolError`] when the request violates the boundary contract or cannot be
/// represented within the sync v1 wire limits.
pub fn encode_request(request: &Request) -> Result<Vec<u8>, ProtocolError> {
    validate_request(request)?;
    let mut writer = Writer::new();
    writer.bytes.extend(MAGIC);
    writer.put_u16(PROTOCOL_VERSION);
    writer.put_u8(request.backend as u8);
    writer.put_i64(request.timestamp_tolerance_ms);
    writer.put_u32(
        u32::try_from(request.local.len())
            .map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for item in &request.local {
        writer.string(&item.path)?;
        writer.put_i64(item.last_modified);
        writer.optional_i64(item.size);
        writer.optional_string(item.fingerprint.as_deref())?;
    }
    writer.put_u32(
        u32::try_from(request.remote.len())
            .map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for item in &request.remote {
        writer.string(&item.path)?;
        writer.optional_string(item.etag.as_deref())?;
        writer.optional_i64(item.last_modified);
        writer.optional_i64(item.size);
        writer.optional_string(item.fingerprint.as_deref())?;
    }
    writer.put_u32(
        u32::try_from(request.metadata.len())
            .map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for item in &request.metadata {
        writer.string(&item.path)?;
        writer.optional_string(item.etag.as_deref())?;
        writer.optional_i64(item.remote_last_modified);
        writer.optional_i64(item.local_last_modified);
        writer.optional_string(item.local_fingerprint.as_deref())?;
        writer.put_i64(item.last_synced_at);
    }
    encode_actions(&mut writer, &request.pre_resolved)?;
    encode_strings(&mut writer, &request.suppressed)?;
    writer.put_u32(
        u32::try_from(request.missing_remote_verification.len())
            .map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for (path, verification) in &request.missing_remote_verification {
        writer.string(path)?;
        writer.put_u8(*verification as u8);
    }
    writer.put_u8(request.default_missing_remote_verification as u8);
    let bytes = writer.bytes();
    if bytes.len() > MAX_PAYLOAD_BYTES {
        return Err(ProtocolError::PayloadTooLarge);
    }
    Ok(bytes)
}

/// Decodes and validates a sync v1 plan envelope.
///
/// # Errors
///
/// Returns [`ProtocolError`] when the plan is malformed, unsupported, too large, or reports a
/// pending-change count inconsistent with its actions.
pub fn decode_plan(bytes: &[u8]) -> Result<Plan, ProtocolError> {
    if bytes.len() > MAX_PAYLOAD_BYTES {
        return Err(ProtocolError::PayloadTooLarge);
    }
    let mut reader = Reader::new(bytes);
    if reader.take(4)? != MAGIC {
        return Err(ProtocolError::InvalidMagic);
    }
    let version = reader.u16()?;
    if version != PROTOCOL_VERSION {
        return Err(ProtocolError::UnsupportedVersion(version));
    }
    let actions = decode_actions(&mut reader, "actions")?;
    let pending_changes = reader.u32()?;
    if reader.offset != bytes.len() {
        return Err(ProtocolError::InvalidString {
            field: "trailing_bytes",
        });
    }
    let plan = Plan {
        actions,
        pending_changes,
    };
    let expected = plan
        .actions
        .iter()
        .filter(|action| action.direction != Direction::None)
        .count();
    if plan.pending_changes as usize != expected {
        return Err(ProtocolError::PendingCountMismatch {
            expected,
            actual: plan.pending_changes,
        });
    }
    Ok(plan)
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    const fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn take(&mut self, length: usize) -> Result<&'a [u8], ProtocolError> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or(ProtocolError::Truncated)?;
        let bytes = self
            .bytes
            .get(self.offset..end)
            .ok_or(ProtocolError::Truncated)?;
        self.offset = end;
        Ok(bytes)
    }

    fn u8(&mut self) -> Result<u8, ProtocolError> {
        Ok(*self.take(1)?.first().ok_or(ProtocolError::Truncated)?)
    }

    fn u16(&mut self) -> Result<u16, ProtocolError> {
        let bytes = self.take(2)?;
        let arr: [u8; 2] = bytes
            .try_into()
            .map_err(|_length_mismatch| ProtocolError::Truncated)?;
        Ok(u16::from_le_bytes(arr))
    }

    fn u32(&mut self) -> Result<u32, ProtocolError> {
        let bytes = self.take(4)?;
        let arr: [u8; 4] = bytes
            .try_into()
            .map_err(|_length_mismatch| ProtocolError::Truncated)?;
        Ok(u32::from_le_bytes(arr))
    }

    fn i64(&mut self) -> Result<i64, ProtocolError> {
        let bytes = self.take(8)?;
        let arr: [u8; 8] = bytes
            .try_into()
            .map_err(|_length_mismatch| ProtocolError::Truncated)?;
        Ok(i64::from_le_bytes(arr))
    }

    fn string(&mut self, field: &'static str) -> Result<String, ProtocolError> {
        let length = self.u32()? as usize;
        if length > MAX_STRING_BYTES {
            return Err(ProtocolError::InvalidString { field });
        }
        let value = std::str::from_utf8(self.take(length)?)
            .map_err(|_utf8_error| ProtocolError::InvalidString { field })?
            .to_owned();
        validate_string(field, &value)?;
        Ok(value)
    }

    fn optional_i64(&mut self, field: &'static str) -> Result<Option<i64>, ProtocolError> {
        match self.u8()? {
            0 => Ok(None),
            1 => {
                let value = self.i64()?;
                validate_non_negative(field, Some(value))?;
                Ok(Some(value))
            }
            value => Err(ProtocolError::InvalidEnum { field, value }),
        }
    }

    fn optional_string(&mut self, field: &'static str) -> Result<Option<String>, ProtocolError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.string(field)?)),
            value => Err(ProtocolError::InvalidEnum { field, value }),
        }
    }
}

struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    const fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    fn bytes(self) -> Vec<u8> {
        self.bytes
    }

    fn put_u8(&mut self, value: u8) {
        self.bytes.push(value);
    }

    fn put_u16(&mut self, value: u16) {
        self.bytes.extend(value.to_le_bytes());
    }

    fn put_u32(&mut self, value: u32) {
        self.bytes.extend(value.to_le_bytes());
    }

    fn put_i64(&mut self, value: i64) {
        self.bytes.extend(value.to_le_bytes());
    }

    fn string(&mut self, value: &str) -> Result<(), ProtocolError> {
        let length = u32::try_from(value.len())
            .map_err(|_conversion_error| ProtocolError::OutputOverflow)?;
        self.put_u32(length);
        self.bytes.extend(value.as_bytes());
        Ok(())
    }

    fn optional_i64(&mut self, value: Option<i64>) {
        match value {
            None => self.put_u8(0),
            Some(value) => {
                self.put_u8(1);
                self.put_i64(value);
            }
        }
    }

    fn optional_string(&mut self, value: Option<&str>) -> Result<(), ProtocolError> {
        match value {
            None => self.put_u8(0),
            Some(value) => {
                self.put_u8(1);
                self.string(value)?;
            }
        }
        Ok(())
    }
}

fn decode_request(bytes: &[u8]) -> Result<Request, ProtocolError> {
    let mut reader = Reader::new(bytes);
    if reader.take(4)? != MAGIC {
        return Err(ProtocolError::InvalidMagic);
    }
    let version = reader.u16()?;
    if version != PROTOCOL_VERSION {
        return Err(ProtocolError::UnsupportedVersion(version));
    }
    let backend = match reader.u8()? {
        1 => Backend::S3,
        2 => Backend::WebDav,
        value => {
            return Err(ProtocolError::InvalidEnum {
                field: "backend",
                value,
            });
        }
    };
    let timestamp_tolerance_ms = reader.i64()?;
    let local = decode_local(&mut reader)?;
    let remote = decode_remote(&mut reader)?;
    let metadata = decode_metadata(&mut reader)?;
    let pre_resolved = decode_actions(&mut reader, "pre_resolved")?;
    let suppressed = decode_strings(&mut reader, "suppressed")?;
    let missing_remote_verification = decode_missing_verification(&mut reader)?;
    let default_missing_remote_verification =
        decode_verification(&mut reader, "default_missing_remote_verification")?;
    if reader.offset != bytes.len() {
        return Err(ProtocolError::InvalidString {
            field: "trailing_bytes",
        });
    }
    Ok(Request {
        backend,
        timestamp_tolerance_ms,
        local,
        remote,
        metadata,
        pre_resolved,
        suppressed,
        missing_remote_verification,
        default_missing_remote_verification,
    })
}

fn decode_local(reader: &mut Reader<'_>) -> Result<Vec<LocalSnapshot>, ProtocolError> {
    let count = checked_count(reader.u32()?, "local")?;
    (0..count)
        .map(|_| {
            Ok(LocalSnapshot {
                path: reader.string("local path")?,
                last_modified: reader.i64()?,
                size: reader.optional_i64("local size")?,
                fingerprint: reader.optional_string("local fingerprint")?,
            })
        })
        .collect()
}

fn decode_remote(reader: &mut Reader<'_>) -> Result<Vec<RemoteSnapshot>, ProtocolError> {
    let count = checked_count(reader.u32()?, "remote")?;
    (0..count)
        .map(|_| {
            Ok(RemoteSnapshot {
                path: reader.string("remote path")?,
                etag: reader.optional_string("remote etag")?,
                last_modified: reader.optional_i64("remote last_modified")?,
                size: reader.optional_i64("remote size")?,
                fingerprint: reader.optional_string("remote fingerprint")?,
            })
        })
        .collect()
}

fn decode_metadata(reader: &mut Reader<'_>) -> Result<Vec<MetadataSnapshot>, ProtocolError> {
    let count = checked_count(reader.u32()?, "metadata")?;
    (0..count)
        .map(|_| {
            Ok(MetadataSnapshot {
                path: reader.string("metadata path")?,
                etag: reader.optional_string("metadata etag")?,
                remote_last_modified: reader.optional_i64("metadata remote_last_modified")?,
                local_last_modified: reader.optional_i64("metadata local_last_modified")?,
                local_fingerprint: reader.optional_string("metadata local_fingerprint")?,
                last_synced_at: reader.i64()?,
            })
        })
        .collect()
}

fn decode_actions(
    reader: &mut Reader<'_>,
    field: &'static str,
) -> Result<Vec<Action>, ProtocolError> {
    let count = checked_count(reader.u32()?, field)?;
    let mut paths = BTreeSet::new();
    (0..count)
        .map(|_| {
            let path = reader.string("action path")?;
            validate_path(&path)?;
            if !paths.insert(path.clone()) {
                return Err(ProtocolError::DuplicatePath { field, path });
            }
            Ok(Action {
                path,
                direction: decode_direction(reader.u8()?)?,
                reason: decode_reason(reader.u8()?)?,
            })
        })
        .collect()
}

fn encode_actions(writer: &mut Writer, actions: &[Action]) -> Result<(), ProtocolError> {
    writer.put_u32(
        u32::try_from(actions.len()).map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for action in actions {
        writer.string(&action.path)?;
        writer.put_u8(action.direction as u8);
        writer.put_u8(action.reason as u8);
    }
    Ok(())
}

fn encode_strings(writer: &mut Writer, values: &[String]) -> Result<(), ProtocolError> {
    writer.put_u32(
        u32::try_from(values.len()).map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for value in values {
        writer.string(value)?;
    }
    Ok(())
}

fn decode_strings(
    reader: &mut Reader<'_>,
    field: &'static str,
) -> Result<Vec<String>, ProtocolError> {
    let count = checked_count(reader.u32()?, field)?;
    (0..count).map(|_| reader.string(field)).collect()
}

fn decode_missing_verification(
    reader: &mut Reader<'_>,
) -> Result<Vec<(String, RemoteAbsenceVerification)>, ProtocolError> {
    let count = checked_count(reader.u32()?, "missing_remote_verification")?;
    (0..count)
        .map(|_| {
            Ok((
                reader.string("missing path")?,
                decode_verification(reader, "missing_remote_verification")?,
            ))
        })
        .collect()
}

fn checked_count(value: u32, field: &'static str) -> Result<usize, ProtocolError> {
    let value =
        usize::try_from(value).map_err(|_conversion_error| ProtocolError::InvalidCount {
            field,
            value: usize::MAX,
        })?;
    if value > MAX_ITEMS {
        return Err(ProtocolError::InvalidCount { field, value });
    }
    Ok(value)
}

fn decode_verification(
    reader: &mut Reader<'_>,
    field: &'static str,
) -> Result<RemoteAbsenceVerification, ProtocolError> {
    match reader.u8()? {
        0 => Ok(RemoteAbsenceVerification::VerifiedAbsent),
        1 => Ok(RemoteAbsenceVerification::UnverifiedAbsent),
        value => Err(ProtocolError::InvalidEnum { field, value }),
    }
}

const fn decode_direction(value: u8) -> Result<Direction, ProtocolError> {
    match value {
        0 => Ok(Direction::None),
        1 => Ok(Direction::Upload),
        2 => Ok(Direction::Download),
        3 => Ok(Direction::DeleteLocal),
        4 => Ok(Direction::DeleteRemote),
        5 => Ok(Direction::Conflict),
        value => Err(ProtocolError::InvalidEnum {
            field: "direction",
            value,
        }),
    }
}

const fn decode_reason(value: u8) -> Result<Reason, ProtocolError> {
    match value {
        0 => Ok(Reason::Unchanged),
        1 => Ok(Reason::LocalOnly),
        2 => Ok(Reason::RemoteOnly),
        3 => Ok(Reason::LocalNewer),
        4 => Ok(Reason::RemoteNewer),
        5 => Ok(Reason::LocalDeleted),
        6 => Ok(Reason::RemoteDeleted),
        7 => Ok(Reason::SameTimestamp),
        8 => Ok(Reason::Conflict),
        value => Err(ProtocolError::InvalidEnum {
            field: "reason",
            value,
        }),
    }
}

fn encode_plan(plan: &Plan) -> Result<Vec<u8>, ProtocolError> {
    let mut writer = Writer::new();
    writer.bytes.extend(MAGIC);
    writer.put_u16(PROTOCOL_VERSION);
    writer.put_u32(
        u32::try_from(plan.actions.len())
            .map_err(|_conversion_error| ProtocolError::OutputOverflow)?,
    );
    for action in &plan.actions {
        writer.string(&action.path)?;
        writer.put_u8(action.direction as u8);
        writer.put_u8(action.reason as u8);
    }
    writer.put_u32(plan.pending_changes);
    let bytes = writer.bytes();
    if bytes.len() > MAX_PAYLOAD_BYTES {
        return Err(ProtocolError::PayloadTooLarge);
    }
    Ok(bytes)
}
