{%- for bytes in method.byte_arrays %}
    if ({{ bytes.name }} != NULL) {
        (*env)->DeleteLocalRef(env, {{ bytes.name }});
        {{ bytes.name }} = NULL;
    }
{% endfor %}
{%- for vector in method.direct_vectors %}
    if ({{ vector.array }} != NULL) {
        (*env)->DeleteLocalRef(env, {{ vector.array }});
        {{ vector.array }} = NULL;
    }
{% endfor %}
{%- for record in method.record_arrays %}
    if ({{ record.array }} != NULL) {
        (*env)->DeleteLocalRef(env, {{ record.array }});
        {{ record.array }} = NULL;
    }
{% endfor %}
{%- for handle in method.callback_handles %}
    if ({{ handle.handle }} != 0) {
        boltffi_jni_callback_handle_release(boltffi_jni_callback_handle_ref({{ handle.handle }}));
        {{ handle.handle }} = 0;
    }
{% endfor %}
{%- for handle in method.closure_handles %}
    if ({{ handle.handle }} != 0) {
        {{ handle.handle_release }}({{ handle.handle }});
        {{ handle.handle }} = 0;
    }
{% endfor %}
