//! One immutable name observation per paginated directory scan. Metadata stays live per page.

use std::{collections::VecDeque, sync::Arc};

use lomo_core::LomoError;

use crate::{
    error::{conflict, resource_limit, validation},
    exchange::generate_random_nonce,
};

#[derive(Debug)]
pub struct DirectoryListing {
    names: Vec<String>,
    fingerprint: String,
    capability: String,
    path: String,
    retained_bytes: usize,
}

impl DirectoryListing {
    pub fn new(names: Vec<String>, capability: &str, path: &str) -> Result<Self, LomoError> {
        let retained_bytes = names
            .iter()
            .map(|name| name.len() + size_of::<String>())
            .sum();
        if retained_bytes > MAX_RETAINED_BYTES {
            return Err(resource_limit(
                "directory_snapshot_too_large",
                "directory names exceed the 64 MiB pending enumeration budget",
            ));
        }
        Ok(Self {
            names,
            fingerprint: generate_random_nonce()?,
            capability: capability.to_owned(),
            path: path.to_owned(),
            retained_bytes,
        })
    }

    pub fn start(&self, cursor: Option<&str>) -> Result<usize, LomoError> {
        let Some(cursor) = cursor else {
            return Ok(0);
        };
        let (fingerprint, offset) = decode_cursor(cursor)?;
        if fingerprint != self.fingerprint || offset >= self.names.len() {
            return Err(invalid_cursor());
        }
        Ok(offset)
    }

    pub fn names(&self, start: usize, limit: usize) -> impl Iterator<Item = &str> {
        self.names
            .iter()
            .skip(start)
            .take(limit)
            .map(String::as_str)
    }

    pub fn cursor_after(&self, offset: usize) -> Option<String> {
        (offset < self.names.len()).then(|| format!("d2.{}.{offset}", self.fingerprint))
    }
}

const MAX_RETAINED_BYTES: usize = 64 * 1024 * 1024;
const MAX_PENDING_LISTINGS: usize = 16;

/// Only unfinished scans retain names; no descriptor is retained. A completed or evicted
/// observation fails explicitly on resume rather than manufacturing a new enumeration.
#[derive(Debug, Default)]
pub struct DirectoryPages {
    pending: VecDeque<Arc<DirectoryListing>>,
    retained_bytes: usize,
}

impl DirectoryPages {
    pub fn resume(
        &self,
        cursor: &str,
        capability: &str,
        path: &str,
    ) -> Result<Arc<DirectoryListing>, LomoError> {
        let (fingerprint, _) = decode_cursor(cursor)?;
        let listing = self
            .pending
            .iter()
            .find(|entry| entry.fingerprint == fingerprint)
            .ok_or_else(|| {
                conflict(
                    "stale_directory_cursor",
                    "directory snapshot finished or was evicted; restart enumeration",
                )
            })?;
        if listing.capability != capability || listing.path != path {
            return Err(invalid_cursor());
        }
        Ok(Arc::clone(listing))
    }

    pub fn retain(&mut self, listing: Arc<DirectoryListing>) {
        if self
            .pending
            .iter()
            .any(|entry| entry.fingerprint == listing.fingerprint)
        {
            return;
        }
        while self.pending.len() >= MAX_PENDING_LISTINGS
            || self.retained_bytes + listing.retained_bytes > MAX_RETAINED_BYTES
        {
            if let Some(oldest) = self.pending.pop_front() {
                self.retained_bytes -= oldest.retained_bytes;
            }
        }
        self.retained_bytes += listing.retained_bytes;
        self.pending.push_back(listing);
    }

    pub fn complete(&mut self, listing: &DirectoryListing) {
        if let Some(index) = self
            .pending
            .iter()
            .position(|entry| entry.fingerprint == listing.fingerprint)
            && let Some(removed) = self.pending.remove(index)
        {
            self.retained_bytes -= removed.retained_bytes;
        }
    }
}

fn decode_cursor(cursor: &str) -> Result<(&str, usize), LomoError> {
    let (fingerprint, offset) = cursor
        .strip_prefix("d2.")
        .and_then(|raw| raw.rsplit_once('.'))
        .ok_or_else(invalid_cursor)?;
    if fingerprint.len() != 32 || !fingerprint.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(invalid_cursor());
    }
    let offset = offset.parse::<usize>().map_err(|_error| invalid_cursor())?;
    if offset == 0 {
        return Err(invalid_cursor());
    }
    Ok((fingerprint, offset))
}

fn invalid_cursor() -> LomoError {
    validation(
        "invalid_directory_cursor",
        "directory cursor must bind a retained snapshot and a valid bound",
    )
}
