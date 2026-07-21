static jclass {{ callback.global_class }} = NULL;
static jmethodID {{ callback.free_method }} = NULL;
static jmethodID {{ callback.clone_method }} = NULL;
{%- for method in callback.methods %}
static jmethodID {{ method.method_id }} = NULL;
{%- endfor %}
{%- for method in callback.handle_methods %}
{%- match method.completion %}
{%- when Some with (completion) %}
static jmethodID {{ completion.success_method_id }} = NULL;
static jmethodID {{ completion.failure_method_id }} = NULL;
{%- when None %}
{%- endmatch %}
{%- endfor %}

{% include "bridge/jni/callback/free.c" %}

{% include "bridge/jni/callback/clone.c" %}

{%- for method in callback.methods %}
{% include "bridge/jni/callback/method.c" %}
{%- endfor %}

{%- for method in callback.handle_methods %}
{% include "bridge/jni/callback/handle_method.c" %}
{%- endfor %}

{% include "bridge/jni/callback/vtable.c" %}

{% include "bridge/jni/callback/load.c" %}

{% include "bridge/jni/callback/unload.c" %}
