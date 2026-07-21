{%- if method.returns_void %}
{% include "bridge/jni/callback/method/invoke/void.c" %}
{%- else %}
{% include "bridge/jni/callback/method/invoke/value.c" %}
{%- endif %}
