use std::time::{Duration, SystemTime, UNIX_EPOCH};

use url::Url;
use uuid::Uuid;

#[export]
pub fn echo_duration(value: Duration) -> Duration {
    value
}

#[export]
pub fn make_duration(seconds: u64, nanos: u32) -> Duration {
    Duration::new(seconds, nanos)
}

#[export]
pub fn duration_as_millis(value: Duration) -> u64 {
    value.as_millis() as u64
}

#[export]
pub fn echo_system_time(value: SystemTime) -> SystemTime {
    value
}

#[export]
pub fn system_time_to_millis(value: SystemTime) -> u64 {
    value.duration_since(UNIX_EPOCH).unwrap().as_millis() as u64
}

#[export]
pub fn millis_to_system_time(value: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(value)
}

#[export]
pub fn echo_uuid(value: Uuid) -> Uuid {
    value
}

#[export]
pub fn uuid_to_string(value: Uuid) -> String {
    value.to_string()
}

#[export]
pub fn echo_url(value: Url) -> Url {
    value
}

#[export]
pub fn url_to_string(value: Url) -> String {
    value.to_string()
}
