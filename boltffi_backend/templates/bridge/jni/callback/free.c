static void {{ callback.free }}(uint64_t handle) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!boltffi_jni_enter(&env, &attached)) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ callback.free_method }}, (jlong)handle);
    boltffi_jni_clear_exception(env);
    boltffi_jni_exit(env, attached);
}
