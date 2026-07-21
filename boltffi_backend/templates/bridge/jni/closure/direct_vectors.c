{%- for vector in closure.direct_vectors %}
    if ({{ vector.length }} > (uintptr_t)INT32_MAX) {
        boltffi_jni_throw_runtime(env, "BoltFFI vector argument too large for Java array");
{% include "bridge/jni/closure/cleanup.c" %}
        boltffi_jni_clear_exception(env);
        boltffi_jni_exit(env, attached);
{% include "bridge/jni/closure/fail.c" %}
    }
    {{ vector.name }} = (*env)->{{ vector.new_array }}(env, (jsize){{ vector.length }});
    if ({{ vector.name }} == NULL) {
{% include "bridge/jni/closure/cleanup.c" %}
        boltffi_jni_clear_exception(env);
        boltffi_jni_exit(env, attached);
{% include "bridge/jni/closure/fail.c" %}
    }
    (*env)->{{ vector.set_region }}(env, {{ vector.name }}, 0, (jsize){{ vector.length }}, (const {{ vector.element_type }} *){{ vector.pointer }});
    if ((*env)->ExceptionCheck(env)) {
{% include "bridge/jni/closure/cleanup.c" %}
        boltffi_jni_clear_exception(env);
        boltffi_jni_exit(env, attached);
{% include "bridge/jni/closure/fail.c" %}
    }
{%- endfor %}
