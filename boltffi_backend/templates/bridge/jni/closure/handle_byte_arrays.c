{%- for bytes in handle.closure.handle_byte_arrays %}
    {{ bytes.buffer }} = boltffi_jni_byte_array_to_buffer(env, {{ bytes.name }});
    if ((*env)->ExceptionCheck(env)) {
{% include "bridge/jni/closure/handle_cleanup.c" %}
{% include "bridge/jni/closure/handle_fail.c" %}
    }
{%- endfor %}
