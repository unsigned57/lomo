{%- for bytes in handle.closure.handle_byte_arrays %}
    FfiBuf_u8 {{ bytes.buffer }} = {0};
{%- endfor %}
