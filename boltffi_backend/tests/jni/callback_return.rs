use super::rendered_fixture;

#[test]
fn jni_bridge_renders_callback_method_shared_callback_handle_returns() {
    insta::assert_snapshot!(rendered_fixture(
        "callback_return/shared_callback_handle_return"
    ));
}

#[test]
fn jni_bridge_renders_callback_method_nullable_callback_handle_returns() {
    insta::assert_snapshot!(rendered_fixture(
        "callback_return/nullable_callback_handle_return"
    ));
}
