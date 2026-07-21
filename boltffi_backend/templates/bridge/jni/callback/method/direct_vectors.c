{%- for vector in method.direct_vectors %}
{% include "bridge/jni/callback/method/direct_vector.c" %}
{%- endfor %}
