//! Kotlin/Android toolchain pin parity: one declaration site, every copy verified.
//!
//! Amper has no cross-module settings inheritance, so `apps/android/app/module.yaml`
//! is the single declaration site for the JDK/SDK/serialization pins (it already feeds
//! `crate::android::AppMetadata`); every sibling `module.yaml` registered in
//! `apps/android/project.yaml` is a verified copy. `crates/lomo-xtask/src/workspace.rs`
//! owns the NDK version and the native Android API level — `ANDROID_API` is `minSdk`'s
//! seventh copy. Workflow `java-version` literals are the *effective* CI compiler under
//! `selectionMode: javaHome` and must equal `jdk.version`; `ndk/<version>` cache paths
//! and `ndk<major>` cache-key prefixes must equal `NDK_VERSION`.

use std::{collections::BTreeMap, fmt, fs, path::Path};

use anyhow::{Context, Result, bail, ensure};
use serde::Serialize;
use yaml_rust2::Yaml;

use crate::{
    util::load_yaml_document,
    workspace::{ANDROID_API, NDK_VERSION},
};

const APP_MANIFEST: &str = "apps/android/app/module.yaml";
const PROJECT_MANIFEST: &str = "apps/android/project.yaml";
const WORKSPACE_RS: &str = "crates/lomo-xtask/src/workspace.rs";
const WORKFLOW_DIR: &str = ".github/workflows";
const SERIALIZATION_JSON: &str = "org.jetbrains.kotlinx:kotlinx-serialization-json";
const JAVA_VERSION_KEY: &str = "java-version";
const SETUP_JAVA_ACTION: &str = "actions/setup-java";
const MISSING: &str = "<missing>";

/// One pin copy that drifted from (or lost) its authoritative literal.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PinViolation {
    /// Repository-relative file holding the copy.
    pub location: String,
    /// Pin fact, e.g. `jvm.release`, `java-version` or `ndk/<version>`.
    pub pin: String,
    /// Literal found at the copy site; `<missing>` when the declaration vanished.
    pub found: String,
    /// Literal required by the authority.
    pub expected: String,
}

impl fmt::Display for PinViolation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "- {}: {} is {:?}, must equal {:?}",
            self.location, self.pin, self.found, self.expected
        )
    }
}

/// The verified pin set plus every copy site that was checked.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct ToolchainParity {
    /// Canonical pin fact -> literal, including the `workspace.rs`-owned entries.
    pub pins: BTreeMap<String, String>,
    /// `apps/android/**/module.yaml` manifests verified against the canonical pins.
    pub module_manifests: Vec<String>,
    /// `.github/workflows/*` files verified for `java-version`/`ndk` literals.
    pub workflows: Vec<String>,
}

#[derive(Clone, Debug)]
struct Canonical {
    min_sdk: String,
    compile_sdk: String,
    target_sdk: String,
    jvm_release: String,
    jdk_version: String,
    jdk_selection_mode: String,
    junit: String,
    warnings_as_errors: String,
    serialization: String,
}

impl Canonical {
    fn pinned_facts(&self) -> BTreeMap<String, String> {
        BTreeMap::from([
            ("minSdk".to_owned(), self.min_sdk.clone()),
            ("compileSdk".to_owned(), self.compile_sdk.clone()),
            ("targetSdk".to_owned(), self.target_sdk.clone()),
            ("jvm.release".to_owned(), self.jvm_release.clone()),
            ("jdk.version".to_owned(), self.jdk_version.clone()),
            (
                "jdk.selectionMode".to_owned(),
                self.jdk_selection_mode.clone(),
            ),
            ("junit".to_owned(), self.junit.clone()),
            (
                "kotlin.allWarningsAsErrors".to_owned(),
                self.warnings_as_errors.clone(),
            ),
            (
                "serialization.version".to_owned(),
                self.serialization.clone(),
            ),
            ("ANDROID_API".to_owned(), ANDROID_API.to_string()),
            ("NDK_VERSION".to_owned(), NDK_VERSION.to_owned()),
        ])
    }
}

struct Scan {
    canonical: Canonical,
    modules: Vec<String>,
    workflows: Vec<String>,
    violations: Vec<PinViolation>,
}

/// Asserts every toolchain pin copy equals its declaration site.
///
/// # Errors
/// Returns an error listing every drifting copy, or fails closed when a manifest,
/// workflow or required pin declaration is unreadable or malformed.
pub fn check_kotlin_toolchain_parity(root: &Path) -> Result<ToolchainParity> {
    let scan = scan_parity(root)?;
    ensure!(
        scan.violations.is_empty(),
        "kotlin toolchain pin drift (authority: {APP_MANIFEST}; NDK/native API: \
         {WORKSPACE_RS}):\n{}",
        scan.violations
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("\n")
    );
    Ok(ToolchainParity {
        pins: scan.canonical.pinned_facts(),
        module_manifests: scan.modules,
        workflows: scan.workflows,
    })
}

/// Collects every pin drift without aborting on the first one.
///
/// # Errors
/// Returns an error when a manifest, workflow or required pin cannot be read —
/// an unverifiable copy is a failure, not an empty violation list.
pub fn kotlin_toolchain_parity_violations(root: &Path) -> Result<Vec<PinViolation>> {
    Ok(scan_parity(root)?.violations)
}

fn scan_parity(root: &Path) -> Result<Scan> {
    let canonical = load_canonical(root)?;
    let mut violations = Vec::new();
    let mut modules = Vec::new();
    for module in registered_modules(root)? {
        let location = format!("apps/android/{module}/module.yaml");
        let manifest = load_yaml_document(&root.join(&location))
            .with_context(|| format!("failed to load {location}"))?;
        module_pins_violations(&manifest, &location, &canonical, &mut violations)?;
        modules.push(location);
    }
    let android_api = ANDROID_API.to_string();
    if android_api != canonical.min_sdk {
        violations.push(PinViolation {
            location: WORKSPACE_RS.to_owned(),
            pin: "ANDROID_API".to_owned(),
            found: android_api,
            expected: canonical.min_sdk.clone(),
        });
    }
    let workflows = workflow_violations(root, &canonical, &mut violations)?;
    Ok(Scan {
        canonical,
        modules,
        workflows,
        violations,
    })
}

fn load_canonical(root: &Path) -> Result<Canonical> {
    let manifest = load_yaml_document(&root.join(APP_MANIFEST))
        .with_context(|| format!("failed to load {APP_MANIFEST}"))?;
    let required = |pin: &'static str, path: &[&str]| -> Result<String> {
        nested(&manifest, path)
            .and_then(scalar_text)
            .with_context(|| format!("{APP_MANIFEST} must declare settings.{pin}"))
    };
    Ok(Canonical {
        min_sdk: required("android.minSdk", &["settings", "android", "minSdk"])?,
        compile_sdk: required("android.compileSdk", &["settings", "android", "compileSdk"])?,
        target_sdk: required("android.targetSdk", &["settings", "android", "targetSdk"])?,
        jvm_release: required("jvm.release", &["settings", "jvm", "release"])?,
        jdk_version: required("jvm.jdk.version", &["settings", "jvm", "jdk", "version"])?,
        jdk_selection_mode: required(
            "jvm.jdk.selectionMode",
            &["settings", "jvm", "jdk", "selectionMode"],
        )?,
        junit: required("junit", &["settings", "junit"])?,
        warnings_as_errors: required(
            "kotlin.allWarningsAsErrors",
            &["settings", "kotlin", "allWarningsAsErrors"],
        )?,
        serialization: required(
            "kotlin.serialization.version",
            &["settings", "kotlin", "serialization", "version"],
        )?,
    })
}

fn registered_modules(root: &Path) -> Result<Vec<String>> {
    let project = load_yaml_document(&root.join(PROJECT_MANIFEST))
        .with_context(|| format!("failed to load {PROJECT_MANIFEST}"))?;
    project["modules"]
        .as_vec()
        .context("{PROJECT_MANIFEST}: `modules` must be a list")?
        .iter()
        .map(|module| {
            module
                .as_str()
                .map(str::to_owned)
                .with_context(|| format!("{PROJECT_MANIFEST}: module entries must be strings"))
        })
        .collect()
}

fn module_pins_violations(
    manifest: &Yaml,
    location: &str,
    canonical: &Canonical,
    violations: &mut Vec<PinViolation>,
) -> Result<()> {
    // Uniform pins: every registered Amper module must restate them identically.
    for (pin, path, expected) in [
        (
            "jvm.release",
            &["settings", "jvm", "release"][..],
            canonical.jvm_release.as_str(),
        ),
        (
            "jdk.version",
            &["settings", "jvm", "jdk", "version"][..],
            canonical.jdk_version.as_str(),
        ),
        (
            "jdk.selectionMode",
            &["settings", "jvm", "jdk", "selectionMode"][..],
            canonical.jdk_selection_mode.as_str(),
        ),
        (
            "kotlin.allWarningsAsErrors",
            &["settings", "kotlin", "allWarningsAsErrors"][..],
            canonical.warnings_as_errors.as_str(),
        ),
    ] {
        compare_pin(manifest, location, pin, path, expected, true, violations);
    }
    // `junit` is declared only by test-bearing modules; where declared it is the same pin.
    compare_pin(
        manifest,
        location,
        "junit",
        &["settings", "junit"],
        &canonical.junit,
        false,
        violations,
    );
    // Android SDK levels are pinned facts wherever a module declares an Android surface.
    if nested(manifest, &["settings", "android"]).is_some() {
        for key in ["minSdk", "compileSdk"] {
            let expected = if key == "minSdk" {
                canonical.min_sdk.as_str()
            } else {
                canonical.compile_sdk.as_str()
            };
            compare_pin(
                manifest,
                location,
                key,
                &["settings", "android", key],
                expected,
                true,
                violations,
            );
        }
    }
    compare_pin(
        manifest,
        location,
        "targetSdk",
        &["settings", "android", "targetSdk"],
        &canonical.target_sdk,
        false,
        violations,
    );
    if nested(manifest, &["settings", "kotlin", "serialization"]).is_some() {
        compare_pin(
            manifest,
            location,
            "serialization.version",
            &["settings", "kotlin", "serialization", "version"],
            &canonical.serialization,
            true,
            violations,
        );
    }
    serialization_dependency_violations(manifest, location, canonical, violations)
}

fn compare_pin(
    manifest: &Yaml,
    location: &str,
    pin: &str,
    path: &[&str],
    expected: &str,
    required: bool,
    violations: &mut Vec<PinViolation>,
) {
    match nested(manifest, path).and_then(scalar_text) {
        Some(found) if found == expected => {}
        Some(found) => violations.push(PinViolation {
            location: location.to_owned(),
            pin: pin.to_owned(),
            found,
            expected: expected.to_owned(),
        }),
        None if required => violations.push(PinViolation {
            location: location.to_owned(),
            pin: pin.to_owned(),
            found: MISSING.to_owned(),
            expected: expected.to_owned(),
        }),
        None => {}
    }
}

/// The `kotlinx-serialization-json` dependency and the serialization plugin share one
/// version fact; wherever the coordinate appears — any dependency section, any scope —
/// its version segment must equal the plugin pin.
fn serialization_dependency_violations(
    manifest: &Yaml,
    location: &str,
    canonical: &Canonical,
    violations: &mut Vec<PinViolation>,
) -> Result<()> {
    for (key, value) in manifest.as_hash().into_iter().flatten() {
        let Some(section) = key.as_str() else {
            continue;
        };
        let dependency_section = ["dependencies", "test-dependencies"]
            .iter()
            .any(|base| section == *base || section.starts_with(&format!("{base}@")));
        if !dependency_section {
            continue;
        }
        let entries = value
            .as_vec()
            .with_context(|| format!("{location}: `{section}` must be a dependency list"))?;
        for entry in entries {
            let Some(version) = serialization_json_version(entry, location, section)? else {
                continue;
            };
            if version != canonical.serialization {
                violations.push(PinViolation {
                    location: location.to_owned(),
                    pin: format!("{section}: {SERIALIZATION_JSON}"),
                    found: version,
                    expected: canonical.serialization.clone(),
                });
            }
        }
    }
    Ok(())
}

fn serialization_json_version(
    entry: &Yaml,
    location: &str,
    section: &str,
) -> Result<Option<String>> {
    let (coordinate, detail) = match entry {
        Yaml::String(coordinate) => (coordinate.as_str(), None),
        Yaml::Hash(mapping) => {
            ensure!(
                mapping.len() == 1,
                "{location}: `{section}` dependency mapping must hold one coordinate"
            );
            let (coordinate, detail) = mapping.iter().next().context("dependency entry")?;
            (
                coordinate
                    .as_str()
                    .with_context(|| format!("{location}: `{section}` coordinate must be text"))?,
                Some(detail),
            )
        }
        Yaml::Real(_)
        | Yaml::Integer(_)
        | Yaml::Boolean(_)
        | Yaml::Array(_)
        | Yaml::Alias(_)
        | Yaml::Null
        | Yaml::BadValue => {
            bail!("{location}: `{section}` dependency entries must be scalars or single-key maps")
        }
    };
    if coordinate == SERIALIZATION_JSON {
        return Ok(Some(
            detail
                .and_then(|value| nested(value, &["version"]))
                .and_then(scalar_text)
                .unwrap_or_else(|| MISSING.to_owned()),
        ));
    }
    Ok(coordinate
        .strip_prefix(&format!("{SERIALIZATION_JSON}:"))
        .map(str::to_owned))
}

/// Every `java-version` literal and every setup-java step is a copy of `jdk.version`;
/// every `ndk/<version>` cache path and `ndk<major>` key prefix is a copy of
/// `NDK_VERSION`. A workflow file that fails to parse is an error, and a workflow set
/// that pins no JDK or caches no pinned NDK directory fails closed.
fn workflow_violations(
    root: &Path,
    canonical: &Canonical,
    violations: &mut Vec<PinViolation>,
) -> Result<Vec<String>> {
    let directory = root.join(WORKFLOW_DIR);
    let mut files = Vec::new();
    for entry in fs::read_dir(&directory)
        .with_context(|| format!("failed to read {}", directory.display()))?
    {
        let path = entry?.path();
        if path
            .extension()
            .is_some_and(|ext| ext == "yml" || ext == "yaml")
        {
            files.push(path);
        }
    }
    files.sort();
    ensure!(
        !files.is_empty(),
        "{} must contain workflow definitions",
        directory.display()
    );
    let mut names = Vec::new();
    let mut java_version_sites = 0_usize;
    let mut ndk_path_sites = 0_usize;
    for path in files {
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .with_context(|| format!("invalid workflow filename {}", path.display()))?;
        let location = format!("{WORKFLOW_DIR}/{name}");
        let document =
            load_yaml_document(&path).with_context(|| format!("failed to parse {location}"))?;
        let mut observed = WorkflowObserved::default();
        walk_yaml(&document, &mut |node| observed.visit(node));
        java_version_sites += observed.java_versions.len();
        for found in &observed.java_versions {
            if *found != canonical.jdk_version {
                violations.push(PinViolation {
                    location: location.clone(),
                    pin: JAVA_VERSION_KEY.to_owned(),
                    found: found.clone(),
                    expected: canonical.jdk_version.clone(),
                });
            }
        }
        for _ in 0..observed.setup_java_without_pin {
            violations.push(PinViolation {
                location: location.clone(),
                pin: JAVA_VERSION_KEY.to_owned(),
                found: format!("{MISSING} ({SETUP_JAVA_ACTION} step without `{JAVA_VERSION_KEY}`)"),
                expected: canonical.jdk_version.clone(),
            });
        }
        ndk_path_sites += ndk_literal_violations(&location, &observed.strings, violations)?;
        names.push(location);
    }
    ensure!(
        java_version_sites > 0,
        "no workflow pins `{JAVA_VERSION_KEY}`; the CI-installed JDK would drift from \
         {APP_MANIFEST} jvm.jdk.version"
    );
    ensure!(
        ndk_path_sites > 0,
        "no workflow caches `ndk/<version>`; the pinned NDK cache literal is gone"
    );
    Ok(names)
}

/// Scans one workflow's string scalars for `ndk/<version>` and `ndk<major>` copies.
/// Returns the number of `ndk/` cache-path literals found.
fn ndk_literal_violations(
    location: &str,
    strings: &[String],
    violations: &mut Vec<PinViolation>,
) -> Result<usize> {
    let major = NDK_VERSION
        .split('.')
        .next()
        .context("NDK_VERSION must contain a major component")?;
    let key_prefix = format!("ndk{major}");
    let mut sites = 0_usize;
    for text in strings {
        for tail in text.split("ndk").skip(1) {
            if let Some(version) = tail.strip_prefix('/') {
                let claim: String = version
                    .chars()
                    .take_while(|c| c.is_ascii_digit() || *c == '.')
                    .collect();
                ensure!(
                    !claim.is_empty(),
                    "{location}: `ndk/` cache path must carry the literal pinned NDK version"
                );
                sites += 1;
                if claim != NDK_VERSION {
                    violations.push(PinViolation {
                        location: location.to_owned(),
                        pin: "ndk/<version>".to_owned(),
                        found: claim,
                        expected: NDK_VERSION.to_owned(),
                    });
                }
            } else {
                let digits: String = tail.chars().take_while(char::is_ascii_digit).collect();
                if digits.is_empty() {
                    continue;
                }
                let claim = format!("ndk{digits}");
                if claim != key_prefix {
                    violations.push(PinViolation {
                        location: location.to_owned(),
                        pin: "ndk<major> cache-key prefix".to_owned(),
                        found: claim,
                        expected: key_prefix.clone(),
                    });
                }
            }
        }
    }
    Ok(sites)
}

#[derive(Default)]
struct WorkflowObserved {
    java_versions: Vec<String>,
    setup_java_without_pin: usize,
    strings: Vec<String>,
}

impl WorkflowObserved {
    fn visit(&mut self, node: &Yaml) {
        if node["uses"]
            .as_str()
            .is_some_and(|uses| uses.contains(SETUP_JAVA_ACTION))
            && nested(node, &["with", JAVA_VERSION_KEY])
                .and_then(scalar_text)
                .is_none()
        {
            self.setup_java_without_pin += 1;
        }
        if let Yaml::Hash(map) = node {
            for (key, value) in map {
                if key.as_str() == Some(JAVA_VERSION_KEY) {
                    self.java_versions
                        .push(scalar_text(value).unwrap_or_else(|| "<non-scalar>".to_owned()));
                }
            }
        }
        if let Some(text) = node.as_str() {
            self.strings.push(text.to_owned());
        }
    }
}

fn walk_yaml<'a>(node: &'a Yaml, visit: &mut impl FnMut(&'a Yaml)) {
    visit(node);
    match node {
        Yaml::Hash(map) => {
            for value in map.values() {
                walk_yaml(value, visit);
            }
        }
        Yaml::Array(items) => {
            for value in items {
                walk_yaml(value, visit);
            }
        }
        Yaml::Real(_)
        | Yaml::Integer(_)
        | Yaml::String(_)
        | Yaml::Boolean(_)
        | Yaml::Alias(_)
        | Yaml::Null
        | Yaml::BadValue => {}
    }
}

fn nested<'a>(node: &'a Yaml, path: &[&str]) -> Option<&'a Yaml> {
    let mut current = node;
    for key in path {
        current = &current[*key];
        if current.is_badvalue() {
            return None;
        }
    }
    Some(current)
}

fn scalar_text(value: &Yaml) -> Option<String> {
    if let Some(text) = value.as_str() {
        return Some(text.to_owned());
    }
    if let Some(int) = value.as_i64() {
        return Some(int.to_string());
    }
    if let Some(real) = value.as_f64() {
        return Some(real.to_string());
    }
    value.as_bool().map(|flag| flag.to_string())
}
