{%- for closure_handle in method.closure_handles %}
    {{ closure_handle.handle }} = {{ closure_handle.handle_new }}(env, {{ closure_handle.call }}, (void *){{ closure_handle.context }}, {{ closure_handle.release }});
    if ((*env)->ExceptionCheck(env)) {
        goto __boltffi_fail;
    }
{%- endfor %}
