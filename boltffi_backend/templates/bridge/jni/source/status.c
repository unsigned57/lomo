static void boltffi_jni_throw_status(JNIEnv *env, FfiStatus status) {
    if (status.code != 0) {
        boltffi_jni_throw_runtime(env, "BoltFFI call failed");
    }
}
