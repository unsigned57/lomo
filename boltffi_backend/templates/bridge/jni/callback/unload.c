static void {{ callback.unload }}(JNIEnv *env) {
    if ({{ callback.global_class }} != NULL) {
        (*env)->DeleteGlobalRef(env, {{ callback.global_class }});
    }
    {{ callback.global_class }} = NULL;
    {{ callback.free_method }} = NULL;
    {{ callback.clone_method }} = NULL;
{%- for method in callback.methods %}
    {{ method.method_id }} = NULL;
{%- endfor %}
{%- for method in callback.handle_methods %}
{%- match method.completion %}
{%- when Some with (completion) %}
    {{ completion.success_method_id }} = NULL;
    {{ completion.failure_method_id }} = NULL;
{%- when None %}
{%- endmatch %}
{%- endfor %}
}
