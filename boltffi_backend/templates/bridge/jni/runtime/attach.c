static jint boltffi_jni_attach_current_thread(JavaVM *vm, JNIEnv **env) {
#if defined(__ANDROID__)
    return (*vm)->AttachCurrentThread(vm, env, NULL);
#else
    return (*vm)->AttachCurrentThread(vm, (void **)env, NULL);
#endif
}
