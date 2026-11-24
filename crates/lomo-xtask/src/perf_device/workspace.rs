//! Corpus-owned workspace measurements. Preparation and verification never enter timed samples.

use super::{
    BenchmarkScenario, CorpusMode, DEFAULT_WORKSPACE_NAME, DeviceBackend, DeviceRun,
    GenerateRequest, Path, Result, Value, direct_workspace_root, ensure, fs, generate_corpus, json,
    percentile,
};
use anyhow::Context;
use std::{
    process::Command,
    time::{SystemTime, UNIX_EPOCH},
};

const SAMPLES: usize = 5;
const MEMOS: usize = 240;
const TASKS: usize = 80;
const ALTERNATE_NAME: &str = "lomo-perf-workspace-alternate";

impl DeviceRun<'_> {
    pub(super) fn dirty_rebuild_probe(&self) -> Result<Value> {
        let root = match self.options.backend {
            DeviceBackend::Saf => format!("/sdcard/Documents/{DEFAULT_WORKSPACE_NAME}"),
            DeviceBackend::Direct => direct_workspace_root(),
        };
        let script = |statement: &str| match self.options.backend {
            DeviceBackend::Saf => self.shell_script(statement),
            DeviceBackend::Direct => self.run_as_script(statement),
        };
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock before unix epoch")?;
        // Group six distinct dated paths under one slot: the anchor and one new file per
        // sample. Scope every shell mutation to these disposable documents.
        let slot_width = SAMPLES as u128 + 1;
        let first_day = now.as_millis() % (1_000 * 12 * 28 / slot_width) * slot_width;
        let fixture = |offset: usize| {
            let day = first_day + offset as u128;
            format!(
                "{root}/{:04}_{:02}_{:02}.md",
                8_000 + day / (12 * 28),
                1 + (day / 28) % 12,
                1 + day % 28,
            )
        };
        let anchor = fixture(0);
        script(&format!(
            "set -C; printf -- '- 23:58:00\nbenchmark dirty rebuild anchor\n' > '{anchor}'"
        ))
        .context("prepare dirty rebuild anchor")?;
        let mut owned_paths = vec![anchor.clone()];
        let measured = (|| {
            self.run_probe("rebuild", &[("samples", "1")], "engine-dirty-prepare")?;
            let mut samples = Vec::with_capacity(SAMPLES);
            let mut runs = Vec::with_capacity(SAMPLES);
            for sample in 0..SAMPLES {
                let added = fixture(sample + 1);
                script(&format!(
                    "set -C; printf -- '- 23:59:00\nbenchmark dirty rebuild new file {sample}\n' > '{added}'"
                ))?;
                owned_paths.push(added);
                script(&format!(
                    "printf -- '- 23:59:{sample:02}\nbenchmark dirty rebuild append {sample}\n' >> '{anchor}'"
                ))?;
                let probe = self.run_probe(
                    "rebuild",
                    &[("samples", "1")],
                    &format!("engine-rebuild-dirty-{sample}"),
                )?;
                let metrics = super::engine_probe_metrics(std::slice::from_ref(&probe))?;
                let values = metrics
                    .values()
                    .next()
                    .context("dirty rebuild has no metric")?;
                ensure!(
                    metrics.len() == 1 && values.len() == 1,
                    "dirty rebuild must produce one timing"
                );
                samples.extend(values.iter().copied());
                runs.push(probe);
            }
            Ok(json!({
                "op": "rebuild", "run": "engine-rebuild-dirty-each-sample", "runs": runs,
                "result": {"status": "ok", "metrics": {"reconcile_ms": samples}}
            }))
        })();
        let paths = owned_paths
            .into_iter()
            .map(|path| format!("'{path}'"))
            .collect::<Vec<_>>()
            .join(" ");
        let cleanup = script(&format!("rm -f {paths}"))
            .and_then(|_| self.run_probe("rebuild", &[("samples", "1")], "engine-dirty-cleanup"))
            .context("clean up dirty rebuild fixtures");
        match (measured, cleanup) {
            (Ok(result), Ok(_)) => Ok(result),
            (Err(error), Ok(_)) | (Ok(_), Err(error)) => Err(error),
            (Err(error), Err(cleanup)) => {
                Err(error.context(format!("dirty fixture cleanup also failed: {cleanup:#}")))
            }
        }
    }

    pub(super) fn workspace_plane(&mut self) -> Value {
        let primary = workspace_location(self.options.backend, false);
        let outcome = self.workspace_measurements(&primary);
        // A failed fixture must not leave a different root selected for subsequent planes.
        let restored = self.run_probe("configure", &[("location", &primary)], "workspace-restore");
        match (outcome, restored) {
            (Ok(mut result), Ok(_)) => {
                super::set_json_field(&mut result, "primary_restored", json!(true));
                result
            }
            (outcome, restored) => {
                let errors = [outcome.err(), restored.err()]
                    .into_iter()
                    .flatten()
                    .map(|error| format!("{error:#}"))
                    .collect::<Vec<_>>()
                    .join("; ");
                self.failures.push(format!("workspace: {errors}"));
                json!({"status": "failed", "error": errors})
            }
        }
    }

    fn workspace_measurements(&self, primary: &str) -> Result<Value> {
        let corpus_dir = self.workspace.corpora_dir().join("device-perf-device");
        let first_index = match self.options.backend {
            DeviceBackend::Saf => {
                // pm clear + record-store reset per iteration is owned by the existing
                // first-install harness. Its trace begins at ALLOW, after picker navigation.
                let result = self.run_scenario(BenchmarkScenario::WorkspaceFirstIndex)?;
                self.workspace_probe(
                    "workspace_reconcile",
                    primary,
                    MEMOS,
                    "seed=1",
                    "first-index-verify",
                )?;
                json!({"endpoint": "grant confirmation to indexed first card", "benchmark": result})
            }
            DeviceBackend::Direct => {
                let mut runs = Vec::new();
                for sample in 0..SAMPLES {
                    self.shell(&["am", "force-stop", "com.lomo.app"])?;
                    let clear = self.shell(&["pm", "clear", "com.lomo.app"])?;
                    ensure!(
                        clear.trim() == "Success",
                        "workspace first-index app reset failed: {clear}"
                    );
                    self.push_to_direct_root(&corpus_dir, &direct_workspace_root())?;
                    runs.push(self.workspace_probe(
                        "workspace_activate",
                        primary,
                        MEMOS,
                        "seed=1",
                        &format!("first-index-{sample}"),
                    )?);
                }
                json!({"endpoint": "production switch call to Ready; picker and rendering excluded", "summary": summarize(&runs)?, "runs": runs})
            }
        };

        // Wait for populated content: an empty main-root shell is not an opened index.
        let reopen = self.run_scenario(BenchmarkScenario::WorkspaceReopen)?;

        let alternate_dir = self
            .workspace
            .corpora_dir()
            .join("device-perf-workspace-alternate");
        if alternate_dir.exists() {
            fs::remove_dir_all(&alternate_dir)?;
        }
        let manifest = generate_corpus(&GenerateRequest {
            seed: 2,
            mode: CorpusMode::Device,
            output_dir: alternate_dir.clone(),
            fixture_root: self.workspace.root.join("fixtures"),
        })?;
        let digest = manifest.canonical_digest()?;
        let alternate = workspace_location(self.options.backend, true);
        self.prepare_workspace_alternate(&alternate_dir)?;
        // Every sample goes from primary A to already-indexed B. No same-root no-op can
        // enter the distribution, and B's seed marker prevents A's projection passing.
        let mut switches = Vec::new();
        for sample in 0..SAMPLES {
            self.workspace_probe(
                "workspace_activate",
                primary,
                MEMOS,
                "seed=1",
                &format!("switch-away-{sample}"),
            )?;
            switches.push(self.workspace_probe(
                "workspace_activate",
                &alternate,
                MEMOS,
                "seed=2",
                &format!("switch-{sample}"),
            )?);
        }

        let mut dirty = Vec::new();
        for sample in 0..SAMPLES {
            self.mutate_workspace_alternate(sample)?;
            // Two new memos and one new document EACH sample, not one dirty + four idle.
            dirty.push(self.workspace_probe(
                "workspace_reconcile",
                &alternate,
                MEMOS + 2 * (sample + 1),
                "seed=2",
                &format!("external-update-{sample}"),
            )?);
        }
        Ok(json!({
            "status": "ok", "backend": self.options.backend.name(), "corpus": self.corpus,
            "alternate_corpus": {"digest": digest, "seed": 2, "files": manifest.files.len(), "memos": MEMOS, "tasks": TASKS},
            "first_index": first_index, "existing_index_reopen": reopen,
            "existing_workspace_switch": {"summary": summarize(&switches)?, "runs": switches},
            "external_update": {"summary": summarize(&dirty)?, "runs": dirty, "new_memos_per_sample": 2, "new_documents_per_sample": 1},
            "scope": "emulator/debuggable; switch and reconcile use production data APIs; only reopen/SAF first index include UI readiness",
        }))
    }

    fn workspace_probe(
        &self,
        op: &str,
        location: &str,
        memos: usize,
        marker: &str,
        name: &str,
    ) -> Result<Value> {
        self.run_probe(
            op,
            &[
                ("location", location),
                ("expectedMemos", &memos.to_string()),
                ("expectedTasks", &TASKS.to_string()),
                ("marker", marker),
            ],
            &format!("workspace-{name}"),
        )
    }

    fn prepare_workspace_alternate(&self, corpus: &Path) -> Result<()> {
        match self.options.backend {
            DeviceBackend::Direct => {
                let root = workspace_location(DeviceBackend::Direct, true);
                self.push_to_direct_root(corpus, &root)?;
                self.workspace_probe(
                    "workspace_activate",
                    &root,
                    MEMOS,
                    "seed=2",
                    "alternate-prepare",
                )?;
            }
            DeviceBackend::Saf => {
                // Reset ONLY the fixed, owned alternate fixture; never remove an active
                // or caller-supplied workspace or preserve a previous fixture's records.
                let root = format!("/sdcard/Documents/{ALTERNATE_NAME}");
                self.shell(&["rm", "-rf", &root])?;
                let mut push = Command::new(self.adb);
                push.args(["-s", self.serial, "push"])
                    .arg(corpus)
                    .arg(&root);
                super::run(&mut push).context("push alternate workspace corpus")?;
                self.shell(&["rm", "-f", &format!("{root}/corpus-manifest.v1.json")])?;
                self.run_harness_method("prepareWorkspace", &[("workspaceName", ALTERNATE_NAME)])?;
            }
        }
        Ok(())
    }

    fn mutate_workspace_alternate(&self, sample: usize) -> Result<()> {
        let root = match self.options.backend {
            DeviceBackend::Saf => format!("/sdcard/Documents/{ALTERNATE_NAME}"),
            DeviceBackend::Direct => workspace_location(DeviceBackend::Direct, true),
        };
        let day = sample + 1;
        let statement = format!(
            "printf -- '- 23:58:{sample:02}\\nseed=2 workspace-append-{sample}\\n' >> '{root}/2020_01_01.md' && printf -- '- 23:59:00\\nseed=2 workspace-added-{sample}\\n' > '{root}/8888_01_{day:02}.md'"
        );
        match self.options.backend {
            DeviceBackend::Saf => self.shell_script(&statement),
            DeviceBackend::Direct => self.run_as_script(&statement),
        }
        .context("mutate alternate fixture before each measured reconcile")?;
        Ok(())
    }
}

fn workspace_location(backend: DeviceBackend, alternate: bool) -> String {
    match (backend, alternate) {
        (DeviceBackend::Direct, false) => direct_workspace_root(),
        (DeviceBackend::Direct, true) => {
            format!("/data/user/0/com.lomo.app/files/{ALTERNATE_NAME}")
        }
        (DeviceBackend::Saf, _) => {
            let name = if alternate {
                ALTERNATE_NAME
            } else {
                DEFAULT_WORKSPACE_NAME
            };
            format!(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F{name}"
            )
        }
    }
}

fn summarize(probes: &[Value]) -> Result<Value> {
    ensure!(
        probes.len() == SAMPLES,
        "workspace phase requires {SAMPLES} samples"
    );
    let mut metrics = serde_json::Map::new();
    for metric in ["operation_ms", "first_page_ms"] {
        let mut samples = Vec::new();
        for probe in probes {
            let values = probe
                .get("result")
                .and_then(|result| result.get("metrics"))
                .and_then(|metrics| metrics.get(metric))
                .and_then(Value::as_array)
                .context("missing workspace metric")?;
            ensure!(
                values.len() == 1,
                "workspace invocation must report one sample"
            );
            let value = values
                .first()
                .and_then(Value::as_f64)
                .context("workspace metric is not numeric")?;
            ensure!(
                value.is_finite() && value >= 0.0,
                "invalid workspace metric"
            );
            samples.push(value);
        }
        metrics.insert(metric.to_owned(), json!({"p50": percentile(&samples, 50), "p95": percentile(&samples, 95), "samples": samples}));
    }
    Ok(Value::Object(metrics))
}
