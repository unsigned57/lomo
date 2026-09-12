use yaml_rust2::Yaml;

use super::{Violation, yaml_document};

const REQUIRED_RULES: &[&str] = &[
    "AppSourceBoundary",
    "AppManifestBoundary",
    "DomainLayerIsolation",
    "DataLayerUiDependency",
    "UiComponentsLayerBoundary",
    "NoSourceSuppressions",
    "NoHandwrittenNativeDeclaration",
    "NoMutableFlowExposure",
    "NoSwallowedCancellationInSuspend",
    "NoSwallowedCancellationInPagingSource",
    "ViewModelSingleStateFlow",
];

pub fn detekt_config_violations(module: &str, source: &str) -> Result<Vec<Violation>, String> {
    let document = yaml_document(source)?;
    let root = document.as_hash().ok_or("config root must be a mapping")?;
    let architecture = root
        .get(&Yaml::String("lomo-architecture".to_owned()))
        .and_then(Yaml::as_hash)
        .ok_or("lomo-architecture ruleset missing")?;
    let mut violations = Vec::new();
    if architecture
        .get(&Yaml::String("active".to_owned()))
        .and_then(Yaml::as_bool)
        != Some(true)
    {
        violations.push(Violation::new(
            "detekt-rule-activation",
            module,
            "lomo-architecture must be explicitly active",
        ));
    }
    for name in REQUIRED_RULES {
        let rule = architecture
            .get(&Yaml::String((*name).to_owned()))
            .and_then(Yaml::as_hash);
        let active = rule
            .and_then(|value| value.get(&Yaml::String("active".to_owned())))
            .and_then(Yaml::as_bool);
        if active != Some(true) {
            violations.push(Violation::new(
                "detekt-rule-activation",
                module,
                format!("{name} must remain active"),
            ));
        }
        if let Some(rule) = rule {
            if let Some(severity) = rule.get(&Yaml::String("severity".to_owned()))
                && severity.as_str() != Some("error")
                && severity.as_str() != Some("Error")
            {
                violations.push(Violation::new(
                    "detekt-rule-severity",
                    module,
                    format!("{name} must fail the gate"),
                ));
            }
            if let Some(excludes) = rule.get(&Yaml::String("excludes".to_owned())) {
                validate_excludes(module, name, excludes, &mut violations)?;
            }
        }
    }
    if architecture.contains_key(&Yaml::String("excludes".to_owned())) {
        violations.push(Violation::new(
            "detekt-rule-exclusion",
            module,
            "the owning ruleset cannot exclude source paths",
        ));
    }
    Ok(violations)
}

fn validate_excludes(
    module: &str,
    rule: &str,
    value: &Yaml,
    violations: &mut Vec<Violation>,
) -> Result<(), String> {
    let entries = value.as_vec().ok_or("rule excludes must be a list")?;
    for entry in entries {
        let path = entry.as_str().ok_or("rule exclusion must be a string")?;
        violations.push(Violation::new(
            "detekt-rule-exclusion",
            module,
            format!("{rule} cannot exclude {path}"),
        ));
    }
    Ok(())
}
