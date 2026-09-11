//! `WebDAV` provider adapter (P5-05) — protocol port only.
//!
//! Compiles and executes provider-neutral intents over HTTP `WebDAV`. Does **not** own direction,
//! conflict, baseline, tombstone, or retry policy. Secrets are held only for the adapter lifetime
//! and must never appear in diagnostics or durable records.

mod adapter;
mod endpoint;
mod multistatus;
mod status_map;
mod transport;

pub use adapter::{
    MapObjectSource, WebDavAdapter, WebDavObjectSource, WorkspaceFileObjectSource,
    connect_map_source, connect_workspace_webdav,
};
pub use endpoint::{WebDavCredentials, WebDavEndpoint};
pub use status_map::map_http_status;
pub use transport::{RemoteCapabilities, is_same_origin};
