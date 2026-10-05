// adversarial-audit: listing digest resolution only when byte-level planning requires;
//                    durable cancel stops ALL subsequent remote publication
//
// Probes:
// - `resolve_listing_digests` skips only `remote_path_in_sync` (strong token == baseline AND
//   local == baseline). A path whose remote strong token still equals the baseline token but whose
//   local bytes changed is resolved anyway — a remote content GET that the planner never consumes
//   (`plan_remote_entry` returns EnsurePresent before touching `entry.digest`). The spec table
//   (已变|未变 → 条件上传) requires no byte-level fact, so the download violates
//   "仅对需要字节判断/搬运的路径下载".
// - `execute_pending_resolved_remote_apply` runs before the page-boundary cancel check in
//   `apply_streaming_intent_pages`. A durable cancel request observed while the record is Running
//   does NOT stop the pending resolved-conflict publish — the "cancel prevents subsequent
//   publication" invariant is breached for that path.
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial probes fail closed on missing facts"
)]
mod tests {
    use std::collections::BTreeMap;
    use std::sync::atomic::{AtomicU32, Ordering};

    use lomo_core::LomoError;
    use lomo_sync::{
        BaselineEntry, BaselineHead, BatchAtomicity, ConflictContentKind, ConflictPathRecord,
        ConflictPathStatus, ConflictSession, ContentDigest, FakeLocalPort, LocalPathEntry,
        PreparedRemoteBatch, PublishReceipt, RemoteCapabilities, RemoteDigestFact,
        RemoteListingStream, RemotePathEntry, RemoteResolvedObject, RemoteSnapshot, RemoteSyncPort,
        RemoteValidator, SessionKind, SnapshotCompleteness, SyncIdentityFence, SyncPath, SyncPaths,
        SyncSession, VerifiedRemoteState, VerifyExpectation, begin_sync_cycle,
        inspect_sync_cycle_plan_with_ports, read_cycle_state, request_sync_cycle_cancel,
        write_baseline, write_conflict_artifact, write_conflict_session, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds-g5").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn digest_of(bytes: &[u8]) -> ContentDigest {
        ContentDigest::from_bytes(bytes)
    }

    /// `RemoteSyncPort` shim over a static listing + object map that counts on-demand
    /// digest resolutions and publish calls (the observations the spec makes load-bearing).
    struct CountingRemote {
        entries: Vec<RemotePathEntry>,
        objects: BTreeMap<String, Vec<u8>>,
        resolve_calls: AtomicU32,
        publish_calls: AtomicU32,
        receipt: PublishReceipt,
        verified: VerifiedRemoteState,
    }

    impl CountingRemote {
        fn publish_count(&self) -> u32 {
            self.publish_calls.load(Ordering::Acquire)
        }

        fn resolve_count(&self) -> u32 {
            self.resolve_calls.load(Ordering::Acquire)
        }
    }

    impl RemoteSyncPort for CountingRemote {
        fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
            RemoteSnapshot::new(SnapshotCompleteness::Complete, self.entries.clone())
        }

        fn list_remote_pages(&self) -> Result<RemoteListingStream, LomoError> {
            let snap = RemoteSnapshot::new(SnapshotCompleteness::Complete, self.entries.clone())?;
            Ok(RemoteListingStream::from_single_snapshot(snap))
        }

        fn batch_atomicity(&self) -> BatchAtomicity {
            BatchAtomicity::PerPath
        }

        fn remote_capabilities(&self) -> Result<RemoteCapabilities, LomoError> {
            Ok(RemoteCapabilities::FULL)
        }

        fn publish(&self, _batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
            self.publish_calls.fetch_add(1, Ordering::AcqRel);
            Ok(PublishReceipt {
                path_results: self.receipt.path_results.clone(),
            })
        }

        fn verify(&self, _exp: &[VerifyExpectation]) -> Result<VerifiedRemoteState, LomoError> {
            Ok(self.verified.clone())
        }

        fn resolve_remote_object(
            &self,
            path: &SyncPath,
        ) -> Result<Option<RemoteResolvedObject>, LomoError> {
            self.resolve_calls.fetch_add(1, Ordering::AcqRel);
            Ok(self
                .objects
                .get(path.as_str())
                .map(|body| RemoteResolvedObject {
                    digest: digest_of(body),
                    body: body.clone(),
                }))
        }

        fn load_object(
            &self,
            path: &SyncPath,
            expected_digest: &ContentDigest,
        ) -> Result<Option<Vec<u8>>, LomoError> {
            Ok(self
                .objects
                .get(path.as_str())
                .filter(|body| digest_of(body).as_str() == expected_digest.as_str())
                .cloned())
        }
    }

    /// Probe: remote strong token still equals the durable baseline token while local bytes
    /// changed → per spec this is a conditional upload with NO byte-level decision. The planner
    /// must not resolve the remote digest. Failure of this assertion documents the over-eager
    /// remote GET.
    #[test]
    fn local_changed_remote_token_unchanged_must_not_download() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-digest").expect("session");
        write_session(&paths, &session).expect("write session");

        let baseline_digest = dig(0x11);
        let local_digest = dig(0x22); // local changed relative to baseline
        let remote_body = b"remote baseline body".to_vec();
        let remote = CountingRemote {
            entries: vec![RemotePathEntry {
                path: SyncPath::parse("memo/a.md").expect("path"),
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong("etag-baseline".to_owned()),
            }],
            objects: BTreeMap::from([("memo/a.md".to_owned(), remote_body)]),
            resolve_calls: AtomicU32::new(0),
            publish_calls: AtomicU32::new(0),
            receipt: PublishReceipt {
                path_results: Vec::new(),
            },
            verified: VerifiedRemoteState {
                results: Vec::new(),
            },
        };

        let mut baseline = BaselineHead::empty();
        baseline.entries.push(BaselineEntry {
            path: "memo/a.md".to_owned(),
            digest: baseline_digest.as_str().to_owned(),
            remote_token: "etag-baseline".to_owned(),
        });
        write_baseline(&paths, &baseline).expect("write baseline");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: SyncPath::parse("memo/a.md").expect("path"),
                digest: local_digest,
            }],
        };

        // Plan-only inspect still performs digest resolution for the plan.
        let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
            .expect("plan inspect");

        // The plan is a conditional upload driven by the strong token.
        assert_eq!(summary.ensure_present_count, 1);
        assert_eq!(
            remote.resolve_count(),
            0,
            "remote body fetched although strong token proves remote==baseline"
        );
    }

    /// Probe: durable cancel request filed against a Running record must stop ALL subsequent
    /// remote publication — including the pending resolved-conflict apply that runs before the
    /// page-boundary cancel check. Failure documents the pre-page publish bypass.
    #[test]
    fn cancel_blocks_pending_resolved_conflict_publish() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-cancel").expect("session");
        write_session(&paths, &session).expect("write session");

        // Durable resolved-KeepLocal conflict: artifact + digest on record.
        let body = b"local winner body".to_vec();
        let artifact =
            write_conflict_artifact(&paths, &session.session_id, "local", "memo/c.md", &body)
                .expect("artifact");
        let record = ConflictPathRecord {
            path: "memo/c.md".to_owned(),
            kind: ConflictContentKind::Markdown,
            local_digest: Some(digest_of(&body).as_str().to_owned()),
            remote_digest: Some(dig(0x33).as_str().to_owned()),
            baseline_digest: Some(dig(0x11).as_str().to_owned()),
            remote_token: Some("etag-conflict".to_owned()),
            local_artifact_ref: Some(artifact),
            remote_artifact_ref: None,
            baseline_artifact_ref: None,
            status: ConflictPathStatus::ResolvedKeepLocal,
        };
        let conflict = ConflictSession::open(fence(), session.session_id.clone(), vec![record])
            .expect("conflict session");
        write_conflict_session(&paths, &conflict).expect("write conflict");

        // Running cycle + durable cancel request bound to its id.
        begin_sync_cycle(&paths, &session, lomo_sync::SyncBackendKind::WebDav, true)
            .expect("begin cycle");
        request_sync_cycle_cancel(&paths).expect("cancel request");

        let remote = CountingRemote {
            entries: Vec::new(),
            objects: BTreeMap::new(),
            resolve_calls: AtomicU32::new(0),
            publish_calls: AtomicU32::new(0),
            receipt: PublishReceipt {
                path_results: Vec::new(),
            },
            verified: VerifiedRemoteState {
                results: Vec::new(),
            },
        };
        let local = FakeLocalPort {
            entries: Vec::new(),
        };

        let outcome = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, true, None);

        // The cycle may report cancelled — the probe is whether publication already happened.
        assert!(
            outcome.is_err() || remote.publish_count() == 0,
            "cancelled cycle must not publish"
        );
        assert_eq!(
            remote.publish_count(),
            0,
            "pending resolved-conflict publish bypasses the durable cancel request"
        );
        let record = read_cycle_state(&paths).expect("read").expect("record");
        assert_ne!(
            record.phase,
            lomo_sync::SyncCyclePhase::Completed,
            "a cancelled cycle must not record Completed"
        );
    }
}
