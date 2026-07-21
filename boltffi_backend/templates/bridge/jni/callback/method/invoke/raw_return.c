{%- if method.returns_byte_array %}
    jbyteArray __boltffi_return_array = (jbyteArray)(*env)->CallStaticObjectMethod(env, {{ callback.global_class }}, {{ method.method_id }}, {{ method.jni_arguments }});
{%- else if method.returns_callback_handle %}
    jlong __boltffi_return_handle = (*env)->CallStaticLongMethod(env, {{ callback.global_class }}, {{ method.method_id }}, {{ method.jni_arguments }});
{%- else if method.returns_closure %}
    jlong __boltffi_return_handle = (*env)->CallStaticLongMethod(env, {{ callback.global_class }}, {{ method.method_id }}, {{ method.jni_arguments }});
{%- else %}
    {{ method.c_return_type }} result = ({{ method.c_return_type }})(*env)->CallStatic{{ method.call_method_suffix }}Method(env, {{ callback.global_class }}, {{ method.method_id }}, {{ method.jni_arguments }});
{%- endif %}
