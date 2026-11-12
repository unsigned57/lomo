//! Two independent Android apps exercise real discovery, consent and durable transfer.

use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, ensure};
use serde_json::{Value, json};

use super::{
    AndroidVariant, UitestOptions, adb_device_serials, adb_install, adb_program, adb_shell_text,
    build, device_facts, git_revision, maestro_program, maestro_version, prepare_device,
    published_debug_apk, run_maestro_flow, uishot_capture_hierarchy, uishot_flatten_hierarchy,
};
use crate::{
    util::{file_sha256, run as run_command},
    workspace::Workspace,
};

const PAIRING_CODE: &str = "benchmark_lan_pairing_code";
const PEER_NAME: &str = "benchmark_lan_peer_name";
const FLOW_DIRECTORY: &str = "apps/android/uitest/lan";

/// Refuses two transports to one Android installation, including redroid aliases.
/// Android IDs, unlike a kernel boot ID, distinguish containers sharing a kernel.
///
/// # Errors
/// Returns an error for missing identities, aliases or an identical serial.
pub fn lan_validate_device_pair(a: &str, b: &str, id_a: &str, id_b: &str) -> Result<()> {
    ensure!(a != b, "LAN test needs two different adb serials");
    for id in [id_a, id_b] {
        ensure!(
            id.len() == 16 && id.bytes().all(|c| c.is_ascii_hexdigit()),
            "LAN test requires a valid Android instance identity, got {id:?}"
        );
    }
    ensure!(
        !id_a.eq_ignore_ascii_case(id_b),
        "LAN test serials refer to the same Android instance"
    );
    Ok(())
}

fn texts(tree: &Value, anchor: &str) -> Result<Vec<String>> {
    uishot_flatten_hierarchy(tree)
        .iter()
        .filter(|node| {
            node["id"]
                .as_str()
                .is_some_and(|id| id.rsplit('/').next() == Some(anchor))
        })
        .map(|node| {
            node["text"]
                .as_str()
                .map(str::to_owned)
                .context("anchored UI text is missing")
        })
        .collect()
}

/// Reads exactly one six-digit code from the actual pairing dialog hierarchy.
///
/// # Errors
/// Rejects absent, duplicate or malformed pairing codes.
pub fn lan_pairing_code(tree: &Value) -> Result<String> {
    let codes = texts(tree, PAIRING_CODE)?;
    ensure!(
        codes.len() == 1,
        "expected one pairing code, found {}",
        codes.len()
    );
    let code = codes.first().context("pairing code is missing")?;
    ensure!(
        code.len() == 6 && code.bytes().all(|c| c.is_ascii_digit()),
        "invalid pairing code"
    );
    Ok(code.clone())
}

/// Allows confirmation only when both real dialog hierarchies carry the same valid code.
///
/// # Errors
/// Rejects malformed, absent or mismatched codes before either device is confirmed.
pub fn lan_verify_pairing_codes(a: &Value, b: &Value) -> Result<()> {
    ensure!(
        lan_pairing_code(a)? == lan_pairing_code(b)?,
        "pairing codes differ; refusing both confirmations"
    );
    Ok(())
}

/// Verifies real discovery is deduplicated and never lists the local device.
///
/// # Errors
/// Rejects an absent/duplicate peer or a self advertisement.
pub fn lan_verify_discovery(tree: &Value, local: &str, peer: &str) -> Result<Value> {
    ensure!(
        !local.chars().any(char::is_whitespace) && !peer.chars().any(char::is_whitespace),
        "LAN test device names must be single tokens"
    );
    // A clickable Material card merges its name and endpoint text in accessibility.
    let rows = texts(tree, PEER_NAME)?;
    let names = rows
        .iter()
        .map(|row| {
            row.split_whitespace()
                .next()
                .map(str::to_owned)
                .context("discovered device row has no name")
        })
        .collect::<Result<Vec<_>>>()?;
    ensure!(
        names.iter().filter(|name| name.as_str() == peer).count() == 1,
        "discovery must show exactly one {peer}; observed {names:?}"
    );
    ensure!(
        !names.iter().any(|name| name == local),
        "discovery listed its own device"
    );
    Ok(json!({"local": local, "peer": peer, "names": names}))
}

fn files(root: &Path, found: &mut Vec<PathBuf>) -> Result<()> {
    for entry in fs::read_dir(root).with_context(|| format!("read {}", root.display()))? {
        let entry = entry?;
        let kind = entry.file_type()?;
        ensure!(
            !kind.is_symlink(),
            "test workspace must not contain symlinks"
        );
        if kind.is_dir() {
            // The test owns one tiny fresh workspace, never an unbounded user corpus.
            ensure!(
                entry.path().components().count() < 64,
                "workspace nesting exceeds test budget"
            );
            files(&entry.path(), found)?;
        } else if kind.is_file() {
            ensure!(found.len() < 512, "workspace exceeds the test file budget");
            ensure!(
                entry.metadata()?.len() <= 4 * 1024 * 1024,
                "workspace file exceeds test byte budget"
            );
            found.push(entry.path());
        }
    }
    Ok(())
}

/// Checks a pulled test-owned workspace, independently of sender UI success.
/// Matching attachments must both have the original bytes and be referenced by the received memo.
///
/// # Errors
/// Rejects missing/duplicated memo markers, missing references and mismatched attachment bytes.
pub fn lan_verify_workspace(
    root: &Path,
    probe: &str,
    expected_count: usize,
    hashes: &[String],
) -> Result<Value> {
    ensure!(!probe.is_empty(), "workspace probe must not be empty");
    let mut paths = Vec::new();
    files(root, &mut paths)?;
    let mut count = 0;
    let mut memo = String::new();
    for path in &paths {
        if path.extension().is_some_and(|extension| extension == "md") {
            let text = fs::read_to_string(path)?;
            let occurrences = text.matches(probe).count();
            count += occurrences;
            if occurrences > 0 {
                memo.push_str(&text);
            }
        }
    }
    ensure!(
        count == expected_count,
        "{probe}: expected {expected_count} durable memo occurrence(s), found {count}"
    );
    let mut attachments = Vec::new();
    for expected in hashes {
        let mut verified = None;
        for path in &paths {
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .context("non-UTF-8 attachment path")?;
            if memo.contains(name) && file_sha256(path)? == *expected {
                verified = Some(path.strip_prefix(root)?.to_string_lossy().into_owned());
                break;
            }
        }
        attachments.push(verified.with_context(|| {
            format!("received memo lacks referenced attachment SHA-256 {expected}")
        })?);
    }
    Ok(
        json!({"probe": probe, "memo_occurrences": count, "attachment_paths": attachments,
        "attachment_sha256": hashes, "workspace": root}),
    )
}

struct Device {
    serial: String,
    name: String,
    peer: String,
    probe: String,
    incoming_probe: String,
    folder: String,
}

impl Device {
    fn remote_root(&self) -> String {
        format!("/sdcard/Download/{}", self.folder)
    }
}

struct Journey<'a> {
    workspace: &'a Workspace,
    adb: PathBuf,
    maestro: PathBuf,
    report_dir: PathBuf,
    evidence: Vec<Value>,
}

impl Journey<'_> {
    fn stage(&mut self, device: &Device, file: &str, label: &str) -> Result<()> {
        let dir = self.report_dir.join(label);
        fs::create_dir_all(&dir)?;
        let result = run_maestro_flow(
            &self.maestro,
            &device.serial,
            &self
                .workspace
                .root
                .join(FLOW_DIRECTORY)
                .join(format!("{file}.yaml")),
            &dir,
            &[
                ("LAN_PROBE", &device.probe),
                ("LAN_DEVICE_NAME", &device.name),
                ("LAN_PEER_NAME", &device.peer),
                ("LAN_INCOMING_PROBE", &device.incoming_probe),
                ("LAN_WORKSPACE", &device.folder),
            ],
        )?;
        let passed = result
            .get("status")
            .and_then(Value::as_str)
            .context("Maestro result is missing its status")?
            == "passed";
        self.evidence
            .push(json!({"step": label, "serial": device.serial, "flow": result}));
        ensure!(passed, "LAN step {label} failed; see {}", dir.display());
        Ok(())
    }

    fn hierarchy(&self, device: &Device, label: &str) -> Result<Value> {
        let text = uishot_capture_hierarchy(&self.maestro, &device.serial)?;
        fs::write(self.report_dir.join(format!("{label}.json")), &text)?;
        serde_json::from_str(&text).context("invalid Maestro hierarchy")
    }

    fn receipt(
        &mut self,
        device: &Device,
        probe: &str,
        count: usize,
        hashes: &[String],
        label: &str,
    ) -> Result<()> {
        let snapshot = self.report_dir.join(label);
        let mut pull = Command::new(&self.adb);
        pull.args(["-s", &device.serial, "pull", &device.remote_root()])
            .arg(&snapshot);
        run_command(&mut pull)?;
        let receipt = lan_verify_workspace(&snapshot, probe, count, hashes)?;
        self.evidence
            .push(json!({"step": label, "serial": device.serial, "receipt": receipt}));
        Ok(())
    }

    fn transfer(&mut self, sender: &Device, receiver: &Device, label: &str) -> Result<()> {
        self.stage(sender, "send", &format!("{label}-send"))?;
        self.stage(receiver, "approve", &format!("{label}-approve"))?;
        self.stage(sender, "success", &format!("{label}-sender-success"))?;
        self.stage(receiver, "received", &format!("{label}-received"))
    }

    fn exercise(&mut self, a: &Device, b: &Device, hashes: &[String]) -> Result<()> {
        for (device, create) in [(a, "create-images"), (b, "create-text")] {
            let cleared =
                adb_shell_text(&self.adb, &device.serial, &["pm", "clear", "com.lomo.app"])?;
            ensure!(
                cleared.trim() == "Success",
                "failed to reset the dedicated LAN test app: {cleared}"
            );
            prepare_device(&self.adb, &device.serial)?;
            adb_shell_text(
                &self.adb,
                &device.serial,
                &["mkdir", "-p", &device.remote_root()],
            )?;
            self.stage(device, "workspace", &format!("{}-workspace", device.name))?;
            self.stage(device, create, &format!("{}-memo", device.name))?;
            self.stage(device, "open-share", &format!("{}-open", device.name))?;
            self.stage(device, "device-name", &format!("{}-name", device.name))?;
        }
        for device in [a, b] {
            self.stage(device, "discover", &format!("{}-discover", device.name))?;
            let tree = self.hierarchy(device, &format!("{}-discovery", device.name))?;
            self.evidence
                .push(lan_verify_discovery(&tree, &device.name, &device.peer)?);
        }
        self.stage(a, "send", "first-contact")?;
        self.stage(a, "pairing", "a-pairing")?;
        self.stage(b, "pairing", "b-pairing")?;
        lan_verify_pairing_codes(
            &self.hierarchy(a, "a-pairing-code")?,
            &self.hierarchy(b, "b-pairing-code")?,
        )?;
        self.evidence
            .push(json!({"step": "matching-pairing-codes", "status": "passed"}));
        self.stage(a, "confirm", "a-confirm")?;
        self.stage(b, "confirm", "b-confirm")?;

        self.stage(a, "send", "rejected-send")?;
        self.stage(b, "reject", "receiver-reject")?;
        self.stage(a, "rejected", "sender-observes-rejection")?;
        self.receipt(b, &a.probe, 0, &[], "rejected-workspace")?;
        self.transfer(a, b, "a-to-b")?;
        self.receipt(b, &a.probe, 1, hashes, "received-images-workspace")?;
        self.stage(b, "open-share", "b-reverse-open")?;
        self.transfer(b, a, "b-to-a")?;
        for (device, incoming, expected_hashes) in [(a, b, &[][..]), (b, a, hashes)] {
            self.stage(device, "restart", &format!("{}-restart", device.name))?;
            self.receipt(
                device,
                &incoming.probe,
                1,
                expected_hashes,
                &format!("{}-durable", device.name),
            )?;
        }
        Ok(())
    }
}

fn install_endpoints(
    workspace: &Workspace,
    options: &UitestOptions,
    adb: &Path,
    endpoints: &[(&str, crate::native::Abi)],
) -> Result<()> {
    let first = endpoints.first().context("LAN endpoints are missing")?;
    let mut abis = Vec::new();
    for (_, abi) in endpoints {
        if !abis.contains(abi) {
            abis.push(*abi);
        }
    }
    if !options.no_install {
        let apk = if options.skip_build {
            ensure!(
                abis.len() == 1,
                "--skip-build requires identical device ABIs"
            );
            published_debug_apk(workspace, first.1)?
        } else {
            build(workspace, AndroidVariant::Debug, &abis)?
        };
        for (serial, _) in endpoints {
            adb_install(adb, serial, &apk)?;
        }
    }
    Ok(())
}

pub(super) fn run(workspace: &Workspace, options: &UitestOptions) -> Result<Value> {
    let peer = options
        .peer_serial
        .as_deref()
        .context("lan_share requires --peer=<serial>")?;
    let sender = std::env::var("ANDROID_SERIAL")
        .context("lan_share requires ANDROID_SERIAL for its sender")?;
    ensure!(sender != peer, "LAN test needs two different adb serials");
    ensure!(
        options.boot.is_none(),
        "lan_share needs two attached devices; omit --redroid"
    );
    ensure!(
        options.clear_data,
        "lan_share requires --clear-data on two dedicated test devices to verify first pairing"
    );
    let adb = adb_program(workspace)?;
    let serials = adb_device_serials(&adb)?;
    for serial in [&sender, peer] {
        ensure!(
            serials.iter().any(|attached| attached == serial),
            "LAN endpoint {serial} is not attached; two Android instances are required"
        );
    }
    let id_a = adb_shell_text(&adb, &sender, &["settings", "get", "secure", "android_id"])?;
    let id_b = adb_shell_text(&adb, peer, &["settings", "get", "secure", "android_id"])?;
    lan_validate_device_pair(&sender, peer, id_a.trim(), id_b.trim())?;
    let facts_a = device_facts(&adb, &sender)?;
    let facts_b = device_facts(&adb, peer)?;
    let maestro = maestro_program(workspace)?;
    let report_dir = workspace.reports_dir().join("android-lan-uitest");
    if report_dir.exists() {
        fs::remove_dir_all(&report_dir)?;
    }
    fs::create_dir_all(&report_dir)?;
    install_endpoints(
        workspace,
        options,
        &adb,
        &[(&sender, facts_a.abi), (peer, facts_b.abi)],
    )?;
    for serial in [&sender, peer] {
        adb_shell_text(&adb, serial, &["run-as", "com.lomo.app", "true"])
            .context("lan_share requires debuggable Lomo installs with benchmark anchors")?;
    }
    let run_id = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis();
    let a = Device {
        serial: sender.clone(),
        name: format!("lomo-a-{run_id}"),
        peer: format!("lomo-b-{run_id}"),
        probe: format!("lan-e2e-{run_id}-from-a"),
        incoming_probe: format!("lan-e2e-{run_id}-from-b"),
        folder: format!("lomo-lan-{run_id}-a"),
    };
    let b = Device {
        serial: peer.to_owned(),
        name: a.peer.clone(),
        peer: a.name.clone(),
        probe: format!("lan-e2e-{run_id}-from-b"),
        incoming_probe: a.probe.clone(),
        folder: format!("lomo-lan-{run_id}-b"),
    };
    let hashes: Vec<_> = ["image-1.png", "image-2.png"]
        .iter()
        .map(|file| file_sha256(&workspace.root.join("apps/android/uitest/assets").join(file)))
        .collect::<Result<_>>()?;
    let mut journey = Journey {
        workspace,
        adb,
        maestro,
        report_dir: report_dir.clone(),
        evidence: Vec::new(),
    };
    let outcome = journey.exercise(&a, &b, &hashes);
    for device in [&a, &b] {
        let capture = adb_shell_text(
            &journey.adb,
            &device.serial,
            &["logcat", "-d", "-t", "1500"],
        );
        match capture {
            Ok(log) => fs::write(report_dir.join(format!("{}.logcat.txt", device.name)), log)?,
            Err(error) => journey
                .evidence
                .push(json!({"log_capture_error": error.to_string(), "serial": device.serial})),
        }
    }
    let report = json!({"schema_version": 1, "kind": "android_lan_uitest", "status": if outcome.is_ok() {"passed"} else {"failed"},
        "devices": [{"serial": sender, "android_id": id_a.trim(), "model": facts_a.model, "api_level": facts_a.api_level, "abilist": facts_a.abilist, "fingerprint": facts_a.fingerprint},
                    {"serial": peer, "android_id": id_b.trim(), "model": facts_b.model, "api_level": facts_b.api_level, "abilist": facts_b.abilist, "fingerprint": facts_b.fingerprint}],
        "maestro_version": maestro_version(&journey.maestro), "git_revision": git_revision(workspace)?,
        "options": {"clear_data": options.clear_data, "skip_build": options.skip_build, "no_install": options.no_install},
        "fixture_sha256": hashes, "evidence": journey.evidence,
        "error": outcome.as_ref().err().map(|error| format!("{error:#}"))});
    fs::write(
        report_dir.join("android-lan-uitest.v1.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    outcome.with_context(|| format!("LAN device test failed; see {}", report_dir.display()))?;
    Ok(report)
}
