use super::rendered_fixture;

#[test]
fn jni_bridge_renders_record_associated_callables() {
    insta::assert_snapshot!(rendered_fixture("associated/record_callables"));
}

#[test]
fn jni_bridge_renders_async_record_methods() {
    insta::assert_snapshot!(rendered_fixture("associated/async_record_method"));
}

#[test]
fn jni_bridge_writes_mutable_direct_record_receivers_back() {
    insta::assert_snapshot!(rendered_fixture("associated/mutable_record_receiver"));
}

#[test]
fn jni_bridge_renders_enum_associated_callables() {
    insta::assert_snapshot!(rendered_fixture("associated/enum_callables"));
}

#[test]
fn jni_bridge_renders_async_enum_methods() {
    insta::assert_snapshot!(rendered_fixture("associated/async_enum_method"));
}
