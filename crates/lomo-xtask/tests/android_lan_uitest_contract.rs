//! Test Change Justification:
//! Reason category: mechanical migration.
//! Old behavior/assertion being replaced: SHA2 digest formatting via `LowerHex`.
//! Why old assertion is no longer correct: SHA2 0.11 returns byte arrays without `LowerHex`.
//! Coverage preserved by: identical input bytes, expected strings, golden vectors and failures.
//! Why this is not fitting the test to the implementation: only digest-to-text API spelling
//! changes; the existing shared encoder retains the same fixed-width lowercase bytes.
//!
//! Behavior Contract:
//! Capability: LAN device tests require two explicit, independent endpoints; owner xtask; P1.
//! Scenarios:
//! - Given the LAN selector without a peer, when dispatched, then it fails before device work.
//! - Given a peer on an ordinary single-device flow, when dispatched, then it rejects the mismatch.
//! - Given aliases, malformed/duplicate codes or self/duplicate discovery rows, then evidence is rejected.
//! - Given received Markdown and linked attachments, when verified, then exactly one durable marker
//!   and original attachment hashes are required; rejection requires zero imported markers.
//! - Given missing, duplicated, corrupt or unreferenced content, when verified, then it fails.
//!
//! Observable outcomes: nonzero CLI exit and the precise rejected precondition.
//! TDD proof: RED `cargo test -p lomo-xtask --test android_lan_uitest_contract --locked`;
//! the old runner treats `lan_share` as a missing YAML and rejects --peer as unknown.
//!
//! Excludes: real multicast, UI consent and transfer; those require the two-device diagnostic.

#[cfg(test)]
mod tests {
    use std::{fs, process::Command};

    use anyhow::{Result, ensure};
    use lomo_xtask::{
        lan_pairing_code, lan_validate_device_pair, lan_verify_discovery, lan_verify_pairing_codes,
        lan_verify_workspace, repository_root,
    };
    use serde_json::{Value, json};
    use sha2::{Digest, Sha256};
    use yaml_rust2::YamlLoader;

    fn hierarchy(anchor: &str, values: &[&str]) -> Value {
        json!({"children": values.iter().map(|text| json!({"attributes": {
            "resource-id": format!("com.lomo.app:id/{anchor}"), "text": text
        }})).collect::<Vec<_>>()})
    }

    #[test]
    fn aliases_and_unknown_android_identities_never_count_as_two_devices() -> Result<()> {
        lan_validate_device_pair("a", "b", "0123456789abcdef", "fedcba9876543210")?;
        ensure!(
            lan_validate_device_pair("a", "a", "0123456789abcdef", "fedcba9876543210").is_err()
        );
        ensure!(
            lan_validate_device_pair(
                "emulator-5554",
                "localhost:5555",
                "0123456789abcdef",
                "0123456789abcdef"
            )
            .is_err()
        );
        for id in ["", "null", "not-an-android-id"] {
            ensure!(lan_validate_device_pair("a", "b", "0123456789abcdef", id).is_err());
        }
        Ok(())
    }

    #[test]
    fn only_one_well_formed_code_in_the_pairing_dialog_can_be_confirmed() -> Result<()> {
        let anchor = "benchmark_lan_pairing_code";
        ensure!(lan_pairing_code(&hierarchy(anchor, &["012345"]))? == "012345");
        lan_verify_pairing_codes(
            &hierarchy(anchor, &["012345"]),
            &hierarchy(anchor, &["012345"]),
        )?;
        ensure!(
            lan_verify_pairing_codes(
                &hierarchy(anchor, &["012345"]),
                &hierarchy(anchor, &["654321"])
            )
            .is_err()
        );
        for values in [
            vec![],
            vec![""],
            vec!["12345"],
            vec!["ABCDEF"],
            vec!["123456", "654321"],
        ] {
            ensure!(lan_pairing_code(&hierarchy(anchor, &values)).is_err());
        }
        ensure!(lan_pairing_code(&hierarchy("unrelated-six-digit-number", &["123456"])).is_err());
        Ok(())
    }

    #[test]
    fn discovery_requires_one_remote_peer_and_excludes_the_local_device() -> Result<()> {
        let anchor = "benchmark_lan_peer_name";
        lan_verify_discovery(&hierarchy(anchor, &["remote"]), "local", "remote")?;
        lan_verify_discovery(
            &hierarchy(anchor, &["remote\n192.168.1.5:1234"]),
            "local",
            "remote",
        )?;
        for values in [
            vec![],
            vec!["other"],
            vec!["remote", "remote"],
            vec!["local", "remote"],
        ] {
            ensure!(lan_verify_discovery(&hierarchy(anchor, &values), "local", "remote").is_err());
        }
        Ok(())
    }

    #[test]
    fn durable_receipts_require_the_received_memo_and_referenced_original_bytes() -> Result<()> {
        let root = tempfile::tempdir()?;
        let bytes = b"the image fixture bytes";
        let hashes = vec![lomo_core::hex_encode(&Sha256::digest(bytes))];
        fs::write(root.path().join("image.png"), bytes)?;
        fs::write(
            root.path().join("2026_10_31.md"),
            "- 12:00:00\nunique-lan-probe\n![image](image.png)\n",
        )?;
        let receipt = lan_verify_workspace(root.path(), "unique-lan-probe", 1, &hashes)?;
        ensure!(receipt.get("memo_occurrences").and_then(Value::as_u64) == Some(1));
        ensure!(receipt.get("attachment_sha256") == Some(&json!(hashes)));
        ensure!(lan_verify_workspace(root.path(), "unique-lan-probe", 0, &[]).is_err());
        ensure!(lan_verify_workspace(root.path(), "missing-lan-probe", 1, &[]).is_err());
        lan_verify_workspace(root.path(), "rejected-lan-probe", 0, &[])?;
        fs::write(root.path().join("image.png"), b"corrupt")?;
        ensure!(lan_verify_workspace(root.path(), "unique-lan-probe", 1, &hashes).is_err());
        fs::write(root.path().join("image.png"), bytes)?;
        fs::write(
            root.path().join("2026_10_31.md"),
            "unique-lan-probe without a referenced attachment",
        )?;
        ensure!(lan_verify_workspace(root.path(), "unique-lan-probe", 1, &hashes).is_err());
        fs::write(root.path().join("another.md"), "unique-lan-probe")?;
        ensure!(lan_verify_workspace(root.path(), "unique-lan-probe", 1, &[]).is_err());
        Ok(())
    }

    #[test]
    fn every_lan_stage_is_valid_maestro_input_and_its_subflows_exist() -> Result<()> {
        let root = repository_root()?.join("apps/android/uitest/lan");
        let mut count = 0;
        for entry in fs::read_dir(&root)? {
            let path = entry?.path();
            if path.extension().is_none_or(|extension| extension != "yaml") {
                continue;
            }
            let documents = YamlLoader::load_from_str(&fs::read_to_string(&path)?)?;
            ensure!(
                documents.len() == 2,
                "{} needs a header and commands",
                path.display()
            );
            let mut documents = documents.iter();
            let header = documents
                .next()
                .ok_or_else(|| anyhow::anyhow!("missing header"))?;
            ensure!(header["appId"].as_str() == Some("com.lomo.app"));
            let body = documents
                .next()
                .and_then(yaml_rust2::Yaml::as_vec)
                .ok_or_else(|| anyhow::anyhow!("missing commands"))?;
            for command in body {
                if let Some(target) = command["runFlow"].as_str() {
                    ensure!(
                        root.join(target).is_file(),
                        "{} references {target}",
                        path.display()
                    );
                }
            }
            count += 1;
        }
        ensure!(count >= 18, "LAN journey stages are missing");
        Ok(())
    }

    #[test]
    fn lan_requires_an_explicit_peer_before_device_work() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["uitest", "lan_share"])
            .env_remove("ANDROID_SERIAL")
            .output()?;
        ensure!(!output.status.success());
        let error = String::from_utf8(output.stdout)?;
        ensure!(
            error.contains("lan_share requires --peer=<serial>"),
            "{error}"
        );
        Ok(())
    }

    #[test]
    fn ordinary_flows_reject_a_peer_before_device_work() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["uitest", "create_memo", "--peer=other-device"])
            .output()?;
        ensure!(!output.status.success());
        let error = String::from_utf8(output.stdout)?;
        ensure!(
            error.contains("--peer is only valid with lan_share"),
            "{error}"
        );
        Ok(())
    }

    #[test]
    fn destructive_reset_and_mixed_scenarios_require_explicit_valid_selection() -> Result<()> {
        for (args, expected) in [
            (
                vec!["uitest", "lan_share", "--peer=b"],
                "requires --clear-data",
            ),
            (
                vec!["uitest", "lan_share", "create_memo", "--peer=b"],
                "must run by itself",
            ),
            (
                vec!["uitest", "lan_share", "--peer=a", "--clear-data"],
                "two different adb serials",
            ),
            (vec!["uitest", "lan_share", "--peer="], "nonempty serial"),
            (
                vec!["uitest", "lan_share", "--peer=b", "--peer=c"],
                "specified once",
            ),
        ] {
            let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                .args(args)
                .env("ANDROID_SERIAL", "a")
                .output()?;
            ensure!(!output.status.success());
            let error = String::from_utf8(output.stdout)?;
            ensure!(error.contains(expected), "{error}");
        }
        Ok(())
    }
}
