use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

/// Maximum UTF-8 bytes accepted for an inline render request.
pub const MAX_INLINE_RENDER_UTF8_BYTES: usize = 1_048_576;

/// Maximum editable memo body length in Unicode scalar values (matches Kotlin `MemoConstraints`).
pub const MAX_EDITABLE_MEMO_UTF8_CHARS: usize = 100_000;

/// Maximum node count in a single `RenderDocumentV1`.
pub const MAX_RENDER_DOCUMENT_NODES: u32 = 8_192;

/// Maximum semantic nesting depth for Markdown structure.
pub const MAX_SEMANTIC_NESTING_DEPTH: u32 = 64;

/// Maximum UTF-8 bytes for a single IR string payload.
pub const MAX_IR_STRING_UTF8_BYTES: usize = 262_144;

/// Maximum items in one workspace scan page.
pub const MAX_WORKSPACE_SCAN_PAGE_SIZE: u32 = 256;

/// Explicit resource-budget checks for stage-2 document surfaces.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ResourceBudget;

impl ResourceBudget {
    /// Rejects an inline render payload that exceeds 1 MiB UTF-8.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error when `byte_len` is greater than
    /// [`MAX_INLINE_RENDER_UTF8_BYTES`].
    pub fn check_inline_render_bytes(byte_len: usize) -> Result<(), LomoError> {
        if byte_len > MAX_INLINE_RENDER_UTF8_BYTES {
            return Err(resource_limit(
                "inline_render_too_large",
                "inline render request exceeds the 1 MiB UTF-8 limit",
            ));
        }
        Ok(())
    }

    /// Rejects an editable memo body longer than `100_000` Unicode scalar values.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error when `char_len` exceeds
    /// [`MAX_EDITABLE_MEMO_UTF8_CHARS`].
    pub fn check_editable_memo_chars(char_len: usize) -> Result<(), LomoError> {
        if char_len > MAX_EDITABLE_MEMO_UTF8_CHARS {
            return Err(resource_limit(
                "editable_memo_too_large",
                "editable memo body exceeds MemoConstraints.MAX_MEMO_LENGTH",
            ));
        }
        Ok(())
    }

    /// Rejects a render document with too many nodes.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error when `node_count` exceeds
    /// [`MAX_RENDER_DOCUMENT_NODES`].
    pub fn check_render_document_nodes(node_count: u32) -> Result<(), LomoError> {
        if node_count > MAX_RENDER_DOCUMENT_NODES {
            return Err(resource_limit(
                "render_document_too_large",
                "RenderDocumentV1 exceeds the 8192 node limit",
            ));
        }
        Ok(())
    }

    /// Rejects nesting deeper than the semantic depth ceiling.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error when `depth` exceeds
    /// [`MAX_SEMANTIC_NESTING_DEPTH`].
    pub fn check_semantic_nesting_depth(depth: u32) -> Result<(), LomoError> {
        if depth > MAX_SEMANTIC_NESTING_DEPTH {
            return Err(resource_limit(
                "semantic_nesting_too_deep",
                "semantic nesting exceeds the depth 64 limit",
            ));
        }
        Ok(())
    }

    /// Rejects an IR string larger than 256 KiB UTF-8.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error when `byte_len` exceeds
    /// [`MAX_IR_STRING_UTF8_BYTES`].
    pub fn check_ir_string_bytes(byte_len: usize) -> Result<(), LomoError> {
        if byte_len > MAX_IR_STRING_UTF8_BYTES {
            return Err(resource_limit(
                "ir_string_too_large",
                "IR string exceeds the 256 KiB UTF-8 limit",
            ));
        }
        Ok(())
    }

    /// Rejects a workspace scan page size outside 1..=256.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error unless `page_size` is within
    /// 1..=[`MAX_WORKSPACE_SCAN_PAGE_SIZE`].
    pub fn check_workspace_scan_page_size(page_size: u32) -> Result<(), LomoError> {
        if !(1..=MAX_WORKSPACE_SCAN_PAGE_SIZE).contains(&page_size) {
            return Err(resource_limit(
                "invalid_workspace_scan_page_size",
                "workspace scan page size must be within 1..=256",
            ));
        }
        Ok(())
    }
}

pub fn validation(code: &'static str, diagnostic: &'static str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn resource_limit(code: &'static str, diagnostic: &'static str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::ResourceLimit,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn corruption(code: &'static str, diagnostic: &'static str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Corruption,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}
