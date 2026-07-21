{%- if method.returns_bytes %}
{% include "bridge/jni/callback/method/invoke/return/bytes.c" %}
{%- else if method.returns_record %}
{% include "bridge/jni/callback/method/invoke/return/record.c" %}
{%- else if method.returns_callback_handle %}
{% include "bridge/jni/callback/method/invoke/return/callback_handle.c" %}
{%- else if method.returns_closure %}
{% include "bridge/jni/callback/method/invoke/return/closure.c" %}
{%- endif %}
