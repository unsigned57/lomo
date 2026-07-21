#[cfg(not(miri))]
unsafe extern "C" {
    fn boltffi_tests_run_glue_harness() -> i32;
}

#[cfg(not(miri))]
#[test]
fn generated_c_glue_harness_runs() {
    assert!(!boltffi_tests::contract::ASSERTED_SYMBOLS.is_empty());
    let code = unsafe { boltffi_tests_run_glue_harness() };
    assert_eq!(code, 0);
}
