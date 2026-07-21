{%- if closure.returns_void %}
        return;
{%- else %}
        return {{ closure.failure_value }};
{%- endif %}
