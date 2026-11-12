//! Behavior Contract: measure established idle planning through the real streaming planner.
//! Given matching local, remote and baseline facts, planning must emit no intents at every size.
//! Reports release host CPU time; excludes transport, durable record I/O, SAF and UI latency.
//! TDD proof: regression preservation via sync package contracts; benchmark compares the same
//! public planner before and after replacing repeated linear baseline lookups.

use std::{
    error::Error,
    io::{self, Write},
    time::Instant,
};

use lomo_sync::{
    BaselineEntry, BaselineHead, ContentDigest, LocalPathEntry, LocalSnapshot,
    MAX_ACTION_PAGE_ITEMS, RemoteDigestFact, RemotePathEntry, RemoteValidator, SessionKind,
    SnapshotCompleteness, SyncIdentityFence, SyncPath, TombstoneSet, plan_intents_streaming,
};
use serde_json::json;

fn main() -> Result<(), Box<dyn Error>> {
    let mut observations = Vec::new();
    for count in [1_000, 5_000, 10_000, 20_000] {
        let digest = ContentDigest::parse(&"ab".repeat(32))?;
        let mut local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(SyncIdentityFence {
            workspace_generation: "probe-generation".into(),
            remote_dataset_id: "probe-dataset".into(),
            remote_identity_digest: "probe-identity".into(),
        });
        let mut remote = Vec::new();
        for index in 0..count {
            let path = SyncPath::parse(&format!("memo/{index:06}.md"))?;
            local.entries.push(LocalPathEntry {
                path: path.clone(),
                digest: digest.clone(),
            });
            baseline.entries.push(BaselineEntry {
                path: path.as_str().into(),
                digest: digest.as_str().into(),
                remote_token: "\"same\"".into(),
            });
            remote.push(RemotePathEntry {
                path,
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong("\"same\"".into()),
            });
        }
        let pages: Vec<_> = remote
            .chunks(MAX_ACTION_PAGE_ITEMS)
            .map(<[_]>::to_vec)
            .collect();
        let mut samples = Vec::new();
        for _ in 0..5 {
            // Prepare the same owned page input outside the timing on both sides.
            let input = pages.clone().into_iter().map(Ok);
            let start = Instant::now();
            let plan = plan_intents_streaming(
                SessionKind::Incremental,
                &local,
                input,
                SnapshotCompleteness::Complete,
                &baseline,
                &TombstoneSet::empty(),
            )?;
            let millis = start.elapsed().as_secs_f64() * 1_000.0;
            if plan
                .intent_pages
                .iter()
                .any(|page| !page.intents.is_empty())
                || plan.remote_path_key_count != count
            {
                return Err(
                    io::Error::other("idle planner did not preserve all matching paths").into(),
                );
            }
            samples.push(millis);
        }
        samples.sort_by(f64::total_cmp);
        observations.push(json!({"paths":count,"samples_ms":samples,"p50_ms":samples.get(2)}));
    }
    writeln!(
        io::stdout().lock(),
        "{}",
        serde_json::to_string_pretty(&json!({"observations":observations}))?
    )?;
    Ok(())
}
