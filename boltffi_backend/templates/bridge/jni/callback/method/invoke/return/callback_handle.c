    {%- match method.callback_handle_constructor %}
    {%- when Some with (create_handle) %}
    {{ method.c_return_type }} result = {{ create_handle }}((uint64_t)__boltffi_return_handle);
    {%- when None %}
    {{ method.c_return_type }} result = {{ method.failure_value }};
    {%- endmatch %}
