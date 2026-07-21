{%- for bytes in closure.byte_arrays %}
    {{ bytes.name }} = boltffi_jni_bytes_to_byte_array(env, {{ bytes.pointer }}, {{ bytes.length }});
    if ({{ bytes.name }} == NULL) {
        boltffi_jni_clear_exception(env);
{% include "bridge/jni/closure/cleanup.c" %}
        boltffi_jni_exit(env, attached);
{% include "bridge/jni/closure/fail.c" %}
    }
{%- endfor %}
