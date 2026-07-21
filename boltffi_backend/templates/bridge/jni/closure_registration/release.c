static void {{ closure.release }}(void *user_data) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!boltffi_jni_enter(&env, &attached)) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, {{ closure.global_class }}, {{ closure.free_method }}, (jlong)(uintptr_t)user_data);
    boltffi_jni_clear_exception(env);
    boltffi_jni_exit(env, attached);
}
