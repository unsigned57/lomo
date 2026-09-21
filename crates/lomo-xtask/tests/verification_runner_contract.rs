/*
 * Behavior Contract:
 * - Unit under test: verification command-log test counting.
 * - Owning layer: quality orchestration (lomo-xtask).
 * - Priority tier: P0.
 * - Capability: a completed command log yields the number of tests it actually executed.
 *
 * Scenarios:
 * - Given nextest's singular `1 test run:` summary, when parsed, then one executed test is reported.
 * - Given nextest's plural or `run/total` summary, when parsed, then the executed count is reported.
 * - Given zero executed tests, when parsed, then the count is exactly zero, never unreported.
 * - Given the JUnit Platform `tests successful` summary used by the Kotlin suites, when parsed,
 *   then successes are summed without counting container lines.
 * - Given a libtest `test result:` line, when parsed, then passed + failed is reported.
 * - Given a log without any recognized summary, when parsed, then no count is reported.
 * - Given a recognized summary with a non-numeric count, when parsed, then parsing fails.
 *
 * Observable outcomes: the reported `Option<u64>` executed-test count, or a parse error.
 *
 * TDD proof:
 * - Fixtures are the real lines captured from this repository: `cargo nextest run -p boltffi`
 *   (`1 test run: 1 passed`), `cargo nextest run -p lomo-application` (`37/69 tests run`), the
 *   `cargo test` architecture run (`test result: ok. 37 passed`) and `./kotlin test --include-module=domain`.
 * - RED: the pre-fix parser matched only the plural `tests run`, so the boltffi summary was
 *   reported as zero/unreported; these tests fail against that parser.
 * - GREEN: `cargo test -p lomo-xtask --test verification_runner_contract --locked`.
 *
 * Excludes: command execution, exit-status classification and scheduler scheduling.
 */

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract fixtures pin exact counts and fail closed"
)]
mod tests {
    use lomo_xtask::verification::parse_test_count;

    // Captured: cargo nextest run -p boltffi --all-features --locked --no-tests=fail
    const NEXTEST_SINGLE: &str = "\
────────────
 Nextest run ID 72ec4d85-bf77-4da3-adcb-330aae773c5f with nextest profile: default
    Starting 1 test across 2 binaries
        PASS [   0.003s] (1/1) boltffi tests::contract
────────────
     Summary [   0.003s] 1 test run: 1 passed, 0 skipped
";

    // Captured: cargo nextest run -p lomo-application (filtered; run/total ratio form).
    const NEXTEST_RATIO: &str = "\
    Starting 69 tests across 1 binary
     Summary [   0.103s] 37/69 tests run: 36 passed, 1 failed, 0 skipped
";

    // Captured shape of the JUnit Platform console listener that runs the Kotlin suites; the
    // domain module reports one block per tested platform, so successes are summed.
    const KOTLIN_PLATFORM: &str = "\
INFO  Testing module 'domain' for platform 'jvm'...
Test run finished after 294 ms
[         5 containers found      ]
[         5 containers successful ]
[         4 tests found           ]
[         4 tests started         ]
[         4 tests successful      ]
[         0 tests failed          ]
INFO  Testing module 'domain' for platform 'android'...
Test run finished after 261 ms
[         5 containers found      ]
[         5 containers successful ]
[         4 tests found           ]
[         4 tests started         ]
[         4 tests successful      ]
[         0 tests failed          ]
";

    // Captured: cargo test -p lomo-architecture-tests --test architecture --locked
    const LIBTEST: &str = "test result: ok. 37 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 1.36s\n";

    #[test]
    fn singular_nextest_summary_counts_one_test() {
        assert_eq!(parse_test_count(NEXTEST_SINGLE).expect("parse"), Some(1));
    }

    #[test]
    fn plural_and_ratio_nextest_summaries_count_executed_tests() {
        assert_eq!(
            parse_test_count("     Summary [   1.234s] 69 tests run: 69 passed, 0 skipped\n")
                .expect("parse"),
            Some(69)
        );
        assert_eq!(parse_test_count(NEXTEST_RATIO).expect("parse"), Some(37));
    }

    #[test]
    fn zero_executed_tests_are_reported_as_zero_not_unreported() {
        assert_eq!(
            parse_test_count("     Summary [   0.001s] 0 tests run: 0 passed, 0 skipped\n")
                .expect("parse"),
            Some(0)
        );
    }

    #[test]
    fn kotlin_platform_successes_are_summed_without_containers() {
        assert_eq!(parse_test_count(KOTLIN_PLATFORM).expect("parse"), Some(8));
        assert_eq!(
            parse_test_count("[         0 tests successful      ]\n").expect("parse"),
            Some(0)
        );
    }

    #[test]
    fn libtest_result_counts_passed_and_failed() {
        assert_eq!(parse_test_count(LIBTEST).expect("parse"), Some(37));
        assert_eq!(
            parse_test_count("test result: FAILED. 4 passed; 1 failed; 0 ignored\n")
                .expect("parse"),
            Some(5)
        );
    }

    #[test]
    fn logs_without_a_summary_report_no_count() {
        assert_eq!(
            parse_test_count("cargo fmt --all -- --check\n").expect("parse"),
            None
        );
        assert_eq!(
            parse_test_count("    Starting 2 tests across 1 binary\n").expect("parse"),
            None
        );
    }

    #[test]
    fn non_numeric_recognized_summary_is_an_error() {
        let error = parse_test_count("test result: ok. many passed; 0 failed\n")
            .expect_err("non-numeric count");
        assert!(
            error.to_string().contains("invalid libtest count"),
            "{error}"
        );
    }
}
