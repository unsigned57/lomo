{%- for handle in closure.closure_handles %}
    jlong {{ handle.handle }} = 0;
{%- endfor %}
