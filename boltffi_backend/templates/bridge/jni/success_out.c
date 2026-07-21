JNIEXPORT void JNICALL {{ writer.symbol }}(JNIEnv *env, jclass cls, jlong return_out, {{ writer.value_jni_type }} value) {
    (void)cls;
    if (return_out == 0) {
        boltffi_jni_throw_runtime(env, "BoltFFI success out pointer was null");
        return;
    }
{%- if writer.writes_scalar %}
    *(({{ writer.value_c_type }} *)(uintptr_t)return_out) = ({{ writer.value_c_type }})value;
{%- else if writer.writes_bytes %}
    FfiBuf_u8 buffer = boltffi_jni_byte_array_to_buffer(env, value);
    if ((*env)->ExceptionCheck(env)) {
        return;
    }
    *((FfiBuf_u8 *)(uintptr_t)return_out) = buffer;
{%- else if writer.writes_record %}
    boltffi_jni_read_record(env, value, (uintptr_t)sizeof({{ writer.value_c_type }}), (void *)(uintptr_t)return_out);
{%- endif %}
}
