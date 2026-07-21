{%- for bytes in handle.closure.handle_byte_arrays %}
    {{ free_buffer }}({{ bytes.buffer }});
{%- endfor %}
{%- for vector in handle.closure.handle_direct_vectors %}
    if ({{ vector.pointer_local }} != NULL) {
{%- if let Some(stack) = vector.stack_copy %}
        if ({{ stack.needs_release }}) {
            (*env)->{{ vector.releaser }}(env, {{ vector.name }}, {{ vector.pointer_local }}, JNI_ABORT);
        }
{%- else %}
        (*env)->{{ vector.releaser }}(env, {{ vector.name }}, {{ vector.pointer_local }}, JNI_ABORT);
{%- endif %}
    }
{%- endfor %}
