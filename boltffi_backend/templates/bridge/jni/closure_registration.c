{%- for closure in closures %}
{% include "bridge/jni/closure_registration/state.c" %}
{% include "bridge/jni/closure_registration/call.c" %}
{% include "bridge/jni/closure_registration/release.c" %}
{% include "bridge/jni/closure_registration/load.c" %}
{% include "bridge/jni/closure_registration/unload.c" %}
{%- endfor %}
