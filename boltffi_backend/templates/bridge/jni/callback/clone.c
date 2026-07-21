static uint64_t {{ callback.clone }}(uint64_t handle) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!boltffi_jni_enter(&env, &attached)) {
        return 0;
    }
    jlong result = (*env)->CallStaticLongMethod(env, {{ callback.global_class }}, {{ callback.clone_method }}, (jlong)handle);
    if (boltffi_jni_clear_exception(env)) {
        boltffi_jni_exit(env, attached);
        return 0;
    }
    boltffi_jni_exit(env, attached);
    return (uint64_t)result;
}
