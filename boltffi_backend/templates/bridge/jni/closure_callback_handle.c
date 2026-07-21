{%- for handle in closure_handles %}
{% include "bridge/jni/closure_callback_handle/storage.c" %}
{% include "bridge/jni/closure_callback_handle/pointer.c" %}
{% include "bridge/jni/closure_callback_handle/allocation.c" %}
{% include "bridge/jni/closure_callback_handle/release.c" %}
{% include "bridge/jni/closure_callback_handle/call.c" %}
{%- endfor %}
