{%- for callback_handle in method.callback_handles %}
    {{ callback_handle.handle }} = boltffi_jni_callback_handle_new_owned(env, {{ callback_handle.parameter }});
    if ((*env)->ExceptionCheck(env)) {
        goto __boltffi_fail;
    }
{%- endfor %}
