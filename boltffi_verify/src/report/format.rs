use super::VerificationResult;
use crate::rules::Violation;

#[derive(Debug, Clone, Copy, Default)]
pub enum OutputFormat {
    #[default]
    Human,
    Json,
    Compact,
}

pub struct Reporter {
    format: OutputFormat,
}

impl Reporter {
    pub fn new(format: OutputFormat) -> Self {
        Self { format }
    }

    pub fn human() -> Self {
        Self::new(OutputFormat::Human)
    }

    pub fn json() -> Self {
        Self::new(OutputFormat::Json)
    }

    pub fn report(&self, result: &VerificationResult) -> String {
        match self.format {
            OutputFormat::Human => self.format_human(result),
            OutputFormat::Json => self.format_json(result),
            OutputFormat::Compact => self.format_compact(result),
        }
    }

    fn format_human(&self, result: &VerificationResult) -> String {
        let mut output = String::new();

        match result {
            VerificationResult::Verified {
                unit_count,
                rule_count,
                duration,
            } => {
                output.push_str("✓ Verification PASSED\n");
                output.push_str(&format!("  {} functions verified\n", unit_count));
                output.push_str(&format!("  {} rules checked\n", rule_count));
                output.push_str(&format!("  completed in {:?}\n", duration));
                output.push('\n');
                output.push_str("  Guarantees:\n");
                output.push_str("    ✓ All allocations freed\n");
                output.push_str("    ✓ No use-after-free\n");
                output.push_str("    ✓ No double-free\n");
                output.push_str("    ✓ All retains released\n");
                output.push_str("    ✓ No double-release\n");
            }
            VerificationResult::Failed {
                violations,
                duration,
            } => {
                output.push_str("✗ Verification FAILED\n");
                output.push_str(&format!(
                    "  {} error(s), {} warning(s) in {:?}\n\n",
                    result.error_count(),
                    result.warning_count(),
                    duration
                ));

                violations.iter().for_each(|violation| {
                    output.push_str(&self.format_violation(violation));
                    output.push('\n');
                });
            }
        }

        output
    }

    fn format_violation(&self, violation: &Violation) -> String {
        let mut output = String::new();

        let severity_str = violation.severity().as_str();
        output.push_str(&format!(
            "{}[{}]: {}\n",
            severity_str,
            violation.code(),
            violation.message()
        ));

        output.push_str(&format!("  --> {}\n", violation.span.display_location()));

        if let Some(source_line) = violation.span.source_line() {
            let line_num = violation.span.line_number();
            let column = violation.span.column_number();

            output.push_str("   |\n");
            output.push_str(&format!("{:3}| {}\n", line_num, source_line));
            output.push_str(&format!(
                "   | {}^\n",
                " ".repeat(column.as_usize().saturating_sub(1))
            ));
        }

        violation.related_spans.iter().for_each(|related| {
            output.push_str(&format!("  related: {}\n", related.display_location()));
        });

        output
    }

    fn format_json(&self, result: &VerificationResult) -> String {
        match result {
            VerificationResult::Verified {
                unit_count,
                rule_count,
                duration,
            } => {
                format!(
                    r#"{{"status":"verified","units":{},"rules":{},"duration_ms":{}}}"#,
                    unit_count,
                    rule_count,
                    duration.as_millis()
                )
            }
            VerificationResult::Failed {
                violations,
                duration,
            } => {
                let violations_json: Vec<String> = violations
                    .iter()
                    .map(|v| {
                        format!(
                            r#"{{"code":"{}","severity":"{}","message":"{}","location":"{}"}}"#,
                            v.code(),
                            v.severity().as_str(),
                            v.message().replace('"', "\\\""),
                            v.span.display_location()
                        )
                    })
                    .collect();

                format!(
                    r#"{{"status":"failed","violations":[{}],"duration_ms":{}}}"#,
                    violations_json.join(","),
                    duration.as_millis()
                )
            }
        }
    }

    fn format_compact(&self, result: &VerificationResult) -> String {
        match result {
            VerificationResult::Verified { unit_count, .. } => {
                format!("OK: {} functions verified", unit_count)
            }
            VerificationResult::Failed { violations, .. } => violations
                .iter()
                .map(|v| {
                    format!(
                        "{}: {} - {}",
                        v.span.display_location(),
                        v.code(),
                        v.message()
                    )
                })
                .collect::<Vec<_>>()
                .join("\n"),
        }
    }
}

impl Default for Reporter {
    fn default() -> Self {
        Self::human()
    }
}
