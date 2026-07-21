{%- for handle in closure.closure_handles %}
    {{ handle.handle }} = {{ handle.handle_new }}(env, {{ handle.call }}, (void *){{ handle.context }}, {{ handle.release }});
    if ((*env)->ExceptionCheck(env)) {
{% include "bridge/jni/closure/cleanup.c" %}
        boltffi_jni_clear_exception(env);
        boltffi_jni_exit(env, attached);
{%- if closure.returns_void %}
        return;
{%- else %}
        return {{ closure.failure_value }};
{%- endif %}
    }
{%- endfor %}
