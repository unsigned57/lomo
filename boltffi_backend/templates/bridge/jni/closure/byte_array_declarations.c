{%- for bytes in closure.byte_arrays %}
    jbyteArray {{ bytes.name }} = NULL;
{%- endfor %}
