    (*env)->CallStaticVoidMethod(env, {{ callback.global_class }}, {{ method.method_id }}, {{ method.jni_arguments }});
    if (boltffi_jni_clear_exception(env)) {
        goto __boltffi_fail;
    }
{% include "bridge/jni/callback/method/cleanup.c" %}
    boltffi_jni_exit(env, attached);
    return;
