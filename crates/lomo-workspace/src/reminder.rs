use lomo_core::LomoError;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::validation;
use crate::source::{ByteSpan, SourceBytes, SourceFingerprint};
use crate::types::MemoIdentity;

/// Serializable reminder identity and facts crossing scan/document-command boundaries.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ReminderReference {
    pub opaque_id: String,
    pub revision: String,
    pub memo_identity: String,
    pub source_start: u64,
    pub source_end: u64,
    pub token_fingerprint: String,
    #[serde(default)]
    pub fingerprint_ordinal: u32,
    /// Resolved embedded reminder id (`#<hex>` tail). `Some` only when the token carries one and it
    /// is unique inside the memo; `None` for legacy tokens and ambiguous duplicates.
    #[serde(default)]
    pub embedded_id: Option<String>,
    pub token: String,
    pub due_at_local: String,
    pub repeat_count: u32,
    pub fired_count: u32,
    pub done: bool,
    pub interval_minutes: u32,
    pub recurrence_code: String,
}

/// Opaque reminder definition identity bound to memo + token fingerprint + same-fingerprint ordinal.
/// Source span and memo revision are snapshot facts for CAS, not identity.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ReminderRef {
    opaque_id: String,
    revision: SourceFingerprint,
    memo_identity: MemoIdentity,
    source_span: ByteSpan,
    token_fingerprint: SourceFingerprint,
    fingerprint_ordinal: u32,
    embedded_id: Option<String>,
    token: String,
    due_at_local: String,
    repeat_count: u32,
    fired_count: u32,
    done: bool,
    interval_minutes: u32,
    recurrence_code: String,
}

impl ReminderRef {
    pub(super) fn from_source_fact(
        source: &SourceBytes,
        memo_identity: &MemoIdentity,
        source_span: ByteSpan,
        token: &str,
        fingerprint_ordinal: u32,
        embedded_id: Option<String>,
    ) -> Result<Self, LomoError> {
        if source.slice(source_span)? != token {
            return Err(validation(
                "reminder_fact_span_mismatch",
                "reminder fact span must slice the exact token bytes",
            ));
        }
        let token_fingerprint = SourceFingerprint::of_bytes(token.as_bytes());
        let facts = crate::render::reminder_token_facts(token)?;
        if embedded_id
            .as_deref()
            .is_some_and(|id| facts.embedded_id.as_deref() != Some(id))
        {
            return Err(validation(
                "reminder_fact_span_mismatch",
                "resolved embedded reminder id does not match the token",
            ));
        }
        let opaque_id = embedded_id.as_deref().map_or_else(
            || opaque_id(memo_identity, &token_fingerprint, fingerprint_ordinal),
            |id| opaque_id_embedded(memo_identity, id),
        );
        Ok(Self {
            opaque_id,
            revision: source.fingerprint().clone(),
            memo_identity: memo_identity.clone(),
            source_span,
            token_fingerprint,
            fingerprint_ordinal,
            embedded_id,
            token: token.to_owned(),
            due_at_local: facts.due_at_local,
            repeat_count: facts.repeat_count,
            fired_count: facts.fired_count,
            done: facts.done,
            interval_minutes: facts.interval_minutes,
            recurrence_code: facts.recurrence_code,
        })
    }

    #[must_use]
    pub fn opaque_id(&self) -> &str {
        &self.opaque_id
    }

    #[must_use]
    pub const fn revision(&self) -> &SourceFingerprint {
        &self.revision
    }

    #[must_use]
    pub const fn memo_identity(&self) -> &MemoIdentity {
        &self.memo_identity
    }

    #[must_use]
    pub const fn source_span(&self) -> ByteSpan {
        self.source_span
    }

    #[must_use]
    pub const fn token_fingerprint(&self) -> &SourceFingerprint {
        &self.token_fingerprint
    }

    #[must_use]
    pub const fn fingerprint_ordinal(&self) -> u32 {
        self.fingerprint_ordinal
    }

    /// Resolved embedded reminder id; `None` for legacy tokens and ambiguous duplicates.
    #[must_use]
    pub fn embedded_id(&self) -> Option<&str> {
        self.embedded_id.as_deref()
    }

    #[must_use]
    pub fn token(&self) -> &str {
        &self.token
    }

    #[must_use]
    pub fn due_at_local(&self) -> &str {
        &self.due_at_local
    }

    #[must_use]
    pub const fn repeat_count(&self) -> u32 {
        self.repeat_count
    }

    #[must_use]
    pub const fn fired_count(&self) -> u32 {
        self.fired_count
    }

    #[must_use]
    pub const fn done(&self) -> bool {
        self.done
    }

    #[must_use]
    pub const fn interval_minutes(&self) -> u32 {
        self.interval_minutes
    }

    #[must_use]
    pub fn recurrence_code(&self) -> &str {
        &self.recurrence_code
    }

    /// Reconstructs one exact reminder reference from a serialized boundary value.
    ///
    /// # Errors
    ///
    /// Returns validation when any identity, span, token fingerprint, canonical token fact, or
    /// opaque id is inconsistent. Live-document membership and revision are checked by the patch
    /// planner.
    pub fn try_from_reference(reference: ReminderReference) -> Result<Self, LomoError> {
        let revision = SourceFingerprint::parse(&reference.revision)?;
        let memo_identity = MemoIdentity::parse(&reference.memo_identity)?;
        let source_start = usize::try_from(reference.source_start).map_err(|_error| {
            validation(
                "invalid_reminder_reference",
                "reminder source start exceeds platform usize",
            )
        })?;
        let source_end = usize::try_from(reference.source_end).map_err(|_error| {
            validation(
                "invalid_reminder_reference",
                "reminder source end exceeds platform usize",
            )
        })?;
        let source_span = ByteSpan::try_new(source_start, source_end, source_end)?;
        let token_fingerprint = SourceFingerprint::parse(&reference.token_fingerprint)?;
        if SourceFingerprint::of_bytes(reference.token.as_bytes()) != token_fingerprint {
            return Err(validation(
                "invalid_reminder_reference",
                "reminder token fingerprint does not match token bytes",
            ));
        }
        let facts = crate::render::reminder_token_facts(&reference.token)?;
        if facts.due_at_local != reference.due_at_local
            || facts.repeat_count != reference.repeat_count
            || facts.fired_count != reference.fired_count
            || facts.done != reference.done
            || facts.interval_minutes != reference.interval_minutes
            || facts.recurrence_code != reference.recurrence_code
        {
            return Err(validation(
                "invalid_reminder_reference",
                "reminder typed facts do not match the canonical token",
            ));
        }
        if reference
            .embedded_id
            .as_deref()
            .is_some_and(|id| facts.embedded_id.as_deref() != Some(id))
        {
            return Err(validation(
                "invalid_reminder_reference",
                "reminder embedded id does not match the canonical token",
            ));
        }
        let expected_opaque = reference.embedded_id.as_deref().map_or_else(
            || {
                opaque_id(
                    &memo_identity,
                    &token_fingerprint,
                    reference.fingerprint_ordinal,
                )
            },
            |id| opaque_id_embedded(&memo_identity, id),
        );
        if reference.opaque_id != expected_opaque {
            return Err(validation(
                "invalid_reminder_reference",
                "reminder opaque id does not match its definition identity",
            ));
        }
        Ok(Self {
            opaque_id: reference.opaque_id,
            revision,
            memo_identity,
            source_span,
            token_fingerprint,
            fingerprint_ordinal: reference.fingerprint_ordinal,
            embedded_id: reference.embedded_id,
            token: reference.token,
            due_at_local: reference.due_at_local,
            repeat_count: reference.repeat_count,
            fired_count: reference.fired_count,
            done: reference.done,
            interval_minutes: reference.interval_minutes,
            recurrence_code: reference.recurrence_code,
        })
    }

    /// Rebinds this source-local reminder to the canonical memo identity supplied by the storage
    /// owner.  Plain direct-mode files do not carry a time header, so the parser necessarily
    /// creates a synthetic identity; the filename/projection identity must then become the sole
    /// authority while the span, token facts, ordinal, and opaque-id derivation remain intact.
    #[must_use]
    pub fn with_memo_identity(&self, memo_identity: MemoIdentity) -> Self {
        let opaque_id = self.embedded_id.as_deref().map_or_else(
            || {
                opaque_id(
                    &memo_identity,
                    &self.token_fingerprint,
                    self.fingerprint_ordinal,
                )
            },
            |id| opaque_id_embedded(&memo_identity, id),
        );
        Self {
            opaque_id,
            revision: self.revision.clone(),
            memo_identity,
            source_span: self.source_span,
            token_fingerprint: self.token_fingerprint.clone(),
            fingerprint_ordinal: self.fingerprint_ordinal,
            embedded_id: self.embedded_id.clone(),
            token: self.token.clone(),
            due_at_local: self.due_at_local.clone(),
            repeat_count: self.repeat_count,
            fired_count: self.fired_count,
            done: self.done,
            interval_minutes: self.interval_minutes,
            recurrence_code: self.recurrence_code.clone(),
        }
    }
}

impl From<&ReminderRef> for ReminderReference {
    fn from(value: &ReminderRef) -> Self {
        Self {
            opaque_id: value.opaque_id.clone(),
            revision: value.revision.as_str().to_owned(),
            memo_identity: value.memo_identity.as_str().to_owned(),
            source_start: value.source_span.start() as u64,
            source_end: value.source_span.end() as u64,
            token_fingerprint: value.token_fingerprint.as_str().to_owned(),
            fingerprint_ordinal: value.fingerprint_ordinal,
            embedded_id: value.embedded_id.clone(),
            token: value.token.clone(),
            due_at_local: value.due_at_local.clone(),
            repeat_count: value.repeat_count,
            fired_count: value.fired_count,
            done: value.done,
            interval_minutes: value.interval_minutes,
            recurrence_code: value.recurrence_code.clone(),
        }
    }
}

fn opaque_id(
    memo_identity: &MemoIdentity,
    token_fingerprint: &SourceFingerprint,
    fingerprint_ordinal: u32,
) -> String {
    let mut opaque = Sha256::new();
    opaque.update(memo_identity.as_str().as_bytes());
    opaque.update([0]);
    opaque.update(token_fingerprint.as_str().as_bytes());
    opaque.update([0]);
    opaque.update(fingerprint_ordinal.to_le_bytes());
    format!("reminder:{:x}", opaque.finalize())
}

/// Definition identity bound to the durable embedded reminder id — stable across token mutations.
fn opaque_id_embedded(memo_identity: &MemoIdentity, embedded_id: &str) -> String {
    let mut opaque = Sha256::new();
    opaque.update(memo_identity.as_str().as_bytes());
    opaque.update([0]);
    opaque.update(b"embedded");
    opaque.update([0]);
    opaque.update(embedded_id.as_bytes());
    format!("reminder:{:x}", opaque.finalize())
}
