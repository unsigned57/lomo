{% for completion in method.completions %}
    {{ completion.callback }}({{ completion.failure_arguments }});
{% endfor -%}
{% if method.returns_void %}
    return;
{%- else %}
    return {{ method.failure_value }};
{%- endif %}
