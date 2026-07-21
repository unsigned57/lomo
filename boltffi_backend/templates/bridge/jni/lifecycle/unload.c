JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
{%- if uses_continuations %}
        boltffi_jni_continuation_unload(env);
{%- endif %}
{%- for callback in callbacks %}
        {{ callback.unload }}(env);
{%- endfor %}
{%- for closure in closures %}
        {{ closure.unload }}(env);
{%- endfor %}
        if (boltffi_jni_native_class != NULL) {
            (*env)->DeleteGlobalRef(env, boltffi_jni_native_class);
        }
    }
    boltffi_jni_vm = NULL;
    boltffi_jni_native_class = NULL;
}
