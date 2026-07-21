{%- if handle.closure.returns_void %}
        return;
{%- else %}
        return {{ handle.failure_value }};
{%- endif %}
