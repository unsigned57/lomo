{%- for vector in handle.closure.handle_direct_vectors %}
    if ({{ vector.name }} == NULL) {
        boltffi_jni_throw_illegal_argument(env, "BoltFFI array argument was null");
{% include "bridge/jni/closure/handle_cleanup.c" %}
{% include "bridge/jni/closure/handle_fail.c" %}
    }
    {{ vector.length_local }} = (*env)->GetArrayLength(env, {{ vector.name }});
{%- if let Some(stack) = vector.stack_copy %}
    if ({{ vector.length_local }} <= (jsize){{ stack.max_len }}) {
        (*env)->{{ stack.region_getter }}(env, {{ vector.name }}, 0, {{ vector.length_local }}, {{ stack.storage }});
        if ((*env)->ExceptionCheck(env)) {
{% include "bridge/jni/closure/handle_cleanup.c" %}
{% include "bridge/jni/closure/handle_fail.c" %}
        }
        {{ vector.pointer_local }} = {{ stack.storage }};
    } else {
        {{ vector.pointer_local }} = (*env)->{{ vector.getter }}(env, {{ vector.name }}, NULL);
        if ({{ vector.pointer_local }} == NULL) {
{% include "bridge/jni/closure/handle_cleanup.c" %}
{% include "bridge/jni/closure/handle_fail.c" %}
        }
        {{ stack.needs_release }} = true;
    }
{%- else %}
    {{ vector.pointer_local }} = (*env)->{{ vector.getter }}(env, {{ vector.name }}, NULL);
    if ({{ vector.pointer_local }} == NULL) {
{% include "bridge/jni/closure/handle_cleanup.c" %}
{% include "bridge/jni/closure/handle_fail.c" %}
    }
{%- endif %}
{%- endfor %}
