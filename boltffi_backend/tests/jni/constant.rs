use super::rendered_fixture;

#[test]
fn jni_bridge_renders_accessor_constants() {
    insta::assert_snapshot!(rendered_fixture("constant/accessor_constants"));
}
