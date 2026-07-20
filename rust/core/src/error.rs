use std::fmt;

use serde::{Deserialize, Serialize};

const MAX_DIAGNOSTIC_BYTES: usize = 2_048;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum ErrorCategory {
    Validation,
    Permission,
    Corruption,
    Storage,
    Network,
    Authentication,
    Conflict,
    Cancelled,
    Timeout,
    Busy,
    ResourceLimit,
    Internal,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum RetryDisposition {
    Never,
    AfterUserAction,
    Transient,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct LomoError {
    category: ErrorCategory,
    code: String,
    retry_disposition: RetryDisposition,
    operation_id: Option<String>,
    job_id: Option<String>,
    diagnostic: String,
}

impl LomoError {
    /// Reconstructs a structured platform-boundary failure after validating every field.
    ///
    /// # Errors
    ///
    /// Returns a validation error for an invalid code, oversized diagnostic, or malformed optional
    /// operation/job identifier.
    pub fn from_platform_boundary(
        category: ErrorCategory,
        code: &str,
        retry_disposition: RetryDisposition,
        operation_id: Option<&str>,
        job_id: Option<&str>,
        diagnostic: &str,
    ) -> Result<Self, Self> {
        if code.is_empty()
            || code.len() > 128
            || !code
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
            || diagnostic.len() > MAX_DIAGNOSTIC_BYTES
        {
            return Err(Self::validation(
                "invalid_platform_error",
                "platform error code or diagnostic violates the stable boundary",
            ));
        }
        let operation_id = operation_id
            .map(crate::OperationId::parse)
            .transpose()?
            .map(|id| id.as_str().to_owned());
        let job_id = job_id
            .map(crate::JobId::parse)
            .transpose()?
            .map(|id| id.as_str().to_owned());
        Ok(Self {
            category,
            code: code.to_owned(),
            retry_disposition,
            operation_id,
            job_id,
            diagnostic: diagnostic.to_owned(),
        })
    }

    #[must_use]
    pub const fn category(&self) -> ErrorCategory {
        self.category
    }

    #[must_use]
    pub fn code(&self) -> &str {
        &self.code
    }

    #[must_use]
    pub const fn retry_disposition(&self) -> RetryDisposition {
        self.retry_disposition
    }

    #[must_use]
    pub fn operation_id(&self) -> Option<&str> {
        self.operation_id.as_deref()
    }

    #[must_use]
    pub fn job_id(&self) -> Option<&str> {
        self.job_id.as_deref()
    }

    #[must_use]
    pub fn diagnostic(&self) -> &str {
        &self.diagnostic
    }

    pub(crate) fn validation(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::Validation,
            code,
            RetryDisposition::Never,
            diagnostic,
        )
    }

    pub(crate) fn resource_limit(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::ResourceLimit,
            code,
            RetryDisposition::Never,
            diagnostic,
        )
    }

    pub(crate) fn storage(code: &'static str, diagnostic: String) -> Self {
        Self::new(
            ErrorCategory::Storage,
            code,
            RetryDisposition::AfterUserAction,
            diagnostic,
        )
    }

    pub(crate) fn corruption(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::Corruption,
            code,
            RetryDisposition::AfterUserAction,
            diagnostic,
        )
    }

    pub(crate) fn busy(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::Busy,
            code,
            RetryDisposition::Transient,
            diagnostic,
        )
    }

    pub(crate) fn cancelled(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::Cancelled,
            code,
            RetryDisposition::Never,
            diagnostic,
        )
    }

    pub(crate) fn timeout(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::Timeout,
            code,
            RetryDisposition::AfterUserAction,
            diagnostic,
        )
    }

    pub(crate) fn internal(code: &'static str, diagnostic: &'static str) -> Self {
        Self::new(
            ErrorCategory::Internal,
            code,
            RetryDisposition::Never,
            diagnostic,
        )
    }

    pub(crate) fn new(
        category: ErrorCategory,
        code: impl Into<String>,
        retry_disposition: RetryDisposition,
        diagnostic: impl Into<String>,
    ) -> Self {
        let diagnostic = diagnostic.into();
        debug_assert!(diagnostic.len() <= MAX_DIAGNOSTIC_BYTES);
        Self {
            category,
            code: code.into(),
            retry_disposition,
            operation_id: None,
            job_id: None,
            diagnostic,
        }
    }
}

impl fmt::Display for LomoError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}: {}", self.code, self.diagnostic)
    }
}

impl std::error::Error for LomoError {}
