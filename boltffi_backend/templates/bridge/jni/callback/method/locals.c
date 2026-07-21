{%- for bytes in method.byte_arrays %}
    jbyteArray {{ bytes.name }} = NULL;
{%- endfor %}
{%- for vector in method.direct_vectors %}
    {{ vector.array_type }} {{ vector.array }} = NULL;
{%- endfor %}
{%- for record in method.record_arrays %}
    jbyteArray {{ record.array }} = NULL;
{%- endfor %}
{%- for callback_handle in method.callback_handles %}
    jlong {{ callback_handle.handle }} = 0;
{%- endfor %}
{%- for closure_handle in method.closure_handles %}
    jlong {{ closure_handle.handle }} = 0;
{%- endfor %}
