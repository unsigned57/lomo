static void {{ closure.unload }}(JNIEnv *env) {
    if ({{ closure.global_class }} != NULL) {
        (*env)->DeleteGlobalRef(env, {{ closure.global_class }});
    }
    {{ closure.global_class }} = NULL;
    {{ closure.call_method }} = NULL;
    {{ closure.free_method }} = NULL;
}
