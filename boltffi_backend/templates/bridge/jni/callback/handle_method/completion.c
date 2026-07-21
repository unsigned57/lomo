static void {{ completion.function }}(void *context, FfiStatus status{% if completion.has_payload %}, {{ completion.payload_c_type }} result{% endif %}) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!boltffi_jni_enter(&env, &attached)) {
{%- if completion.payload_bytes %}
        {{ free_buffer }}(result);
{%- else if completion.payload_callback_handle %}
        boltffi_jni_release_callback_value(result);
{%- endif %}
        return;
    }
    if (status.code != 0) {
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.failure_method_id }}, (jlong)(uintptr_t)context);
        boltffi_jni_clear_exception(env);
        boltffi_jni_exit(env, attached);
        return;
    }
{%- if completion.payload_bytes %}
    jbyteArray payload = boltffi_jni_buffer_to_byte_array(env, result);
    if ((*env)->ExceptionCheck(env)) {
        boltffi_jni_clear_exception(env);
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.failure_method_id }}, (jlong)(uintptr_t)context);
    } else {
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.success_method_id }}, (jlong)(uintptr_t)context, payload);
    }
    if (payload != NULL) {
        (*env)->DeleteLocalRef(env, payload);
    }
{%- else if completion.payload_record %}
    jbyteArray payload = boltffi_jni_record_to_byte_array(env, &result, (uintptr_t)sizeof(result));
    if ((*env)->ExceptionCheck(env)) {
        boltffi_jni_clear_exception(env);
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.failure_method_id }}, (jlong)(uintptr_t)context);
    } else {
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.success_method_id }}, (jlong)(uintptr_t)context, payload);
    }
    if (payload != NULL) {
        (*env)->DeleteLocalRef(env, payload);
    }
{%- else if completion.payload_callback_handle %}
    jlong payload = boltffi_jni_callback_handle_new_owned(env, result);
    if ((*env)->ExceptionCheck(env)) {
        boltffi_jni_clear_exception(env);
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.failure_method_id }}, (jlong)(uintptr_t)context);
    } else {
        (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.success_method_id }}, (jlong)(uintptr_t)context, payload);
    }
{%- else if completion.has_payload %}
    (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.success_method_id }}, (jlong)(uintptr_t)context, ({{ completion.payload_jni_type }})result);
{%- else %}
    (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ completion.success_method_id }}, (jlong)(uintptr_t)context);
{%- endif %}
    boltffi_jni_clear_exception(env);
    boltffi_jni_exit(env, attached);
}
