{%- for callback in callbacks %}
{% include "bridge/jni/callback/registration.c" %}
{%- endfor %}
