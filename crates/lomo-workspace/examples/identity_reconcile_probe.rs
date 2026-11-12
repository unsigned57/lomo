//! Behavior Contract:
//! Capability: measure external-edit identity reconciliation in the workspace owner.
//! Given unique blocks and an appended block, when reconciliation runs, then every old
//! identity survives and only the appended block remains unbound. Parsing and initial map
//! creation are outside the timed samples. Reports five samples for three document sizes.
//! TDD proof: regression preservation; `memo_identity_locator_contract` runs before/after.
//! Excludes: filesystem I/O, projection publication, ambiguous blocks and device timing.

use lomo_core::OperationId;
use lomo_workspace::{
    MemoId, MemoIdentityMap, SourceBytes, WorkspaceRelativePath, WorkspaceRootId,
    parse_workspace_document,
};
use std::{
    error::Error,
    fmt::Write as _,
    io::{self, Write as _},
    time::Instant,
};

fn main() -> Result<(), Box<dyn Error>> {
    let mut measurements = Vec::new();
    for blocks in [100, 500, 1000] {
        let mut text = String::new();
        let mut ids = Vec::new();
        for index in 0..blocks {
            writeln!(text, "- 10:00\nblock {index}\n")?;
            ids.push(MemoId::parse(&format!("memo-{index}"))?);
        }
        let original = parse_workspace_document(&SourceBytes::try_from_str(&text)?, "2026_10_22")?;
        let map = MemoIdentityMap::initialize(
            OperationId::parse("import")?,
            WorkspaceRootId::Notes,
            WorkspaceRelativePath::parse("2026_10_22.md")?,
            &original,
            ids,
        )?;
        text.push_str("- 11:00\nappended block\n\n");
        let changed = parse_workspace_document(&SourceBytes::try_from_str(&text)?, "2026_10_22")?;
        let mut samples_ms = Vec::new();
        for sample in 0..6 {
            let operation = OperationId::parse(&format!("external-{sample}"))?;
            let start = Instant::now();
            let reconciled = map.reconcile_external(operation, &changed)?;
            let ms = start.elapsed().as_secs_f64() * 1000.0;
            if reconciled.bindings().len() != blocks
                || reconciled.unbound().len() != 1
                || !reconciled.conflicts().is_empty()
            {
                return Err(
                    io::Error::other("external reconciliation lost identity evidence").into(),
                );
            }
            if sample > 0 {
                samples_ms.push(ms);
            }
        }
        measurements.push(serde_json::json!({"blocks": blocks, "samples_ms": samples_ms}));
    }
    writeln!(
        io::stdout().lock(),
        "{}",
        serde_json::to_string_pretty(&measurements)?
    )?;
    Ok(())
}
