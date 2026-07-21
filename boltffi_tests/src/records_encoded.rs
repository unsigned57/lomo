use boltffi::*;

use crate::{FixturePoint, FixtureStatus};

#[data]
#[derive(Clone, Debug, PartialEq, Default)]
pub struct FixtureMessageRecord {
    pub label: String,
    pub anchor: FixturePoint,
    pub status: FixtureStatus,
}

#[data]
#[derive(Clone, Debug, PartialEq, Default)]
pub struct FixtureStringConfig {
    pub name: String,
    pub source: String,
}

#[data]
#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct FixtureMarkerOptions {
    pub id: i32,
}

#[export]
pub fn describe_message(record: FixtureMessageRecord) -> String {
    format!(
        "{}:{}:{}:{:?}",
        record.label, record.anchor.x, record.anchor.y, record.status
    )
}

#[export]
pub fn peek_label(record: &FixtureMessageRecord) -> u32 {
    record.label.len() as u32
}

#[export]
pub fn relabel(record: &mut FixtureMessageRecord, label: String) {
    record.label = label;
}

#[export]
pub fn make_message(label: String) -> FixtureMessageRecord {
    FixtureMessageRecord {
        label,
        anchor: FixturePoint { x: 5.0, y: 8.0 },
        status: FixtureStatus::Active,
    }
}

#[data(impl)]
impl FixtureStringConfig {
    pub fn from_owned_name(name: String) -> Self {
        Self {
            name,
            source: "owned".to_string(),
        }
    }

    pub fn from_borrowed_name(name: &str) -> Self {
        Self {
            name: name.to_string(),
            source: "borrowed".to_string(),
        }
    }

    #[allow(clippy::ptr_arg)]
    pub fn from_string_ref_name(name: &String) -> Self {
        Self {
            name: name.clone(),
            source: "string_ref".to_string(),
        }
    }
}
