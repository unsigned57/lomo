    {%- match method.closure_return %}
    {%- when Some with (closure_return) %}
    if ({{ closure_return.output }} == NULL) {
        {{ closure_return.release }}((void *)(uintptr_t)__boltffi_return_handle);
        goto __boltffi_fail;
    }
    typedef struct {
        {{ closure_return.invoke_field }};
        void *context;
        void (*release)(void *);
    } {{ closure_return.storage }};
    {{ closure_return.storage }} __boltffi_return = {
        .invoke = {{ closure_return.invoke }},
        .context = (void *)(uintptr_t)__boltffi_return_handle,
        .release = {{ closure_return.release }},
    };
    *(({{ closure_return.storage }} *){{ closure_return.output }}) = __boltffi_return;
    {{ method.c_return_type }} result = ({{ method.c_return_type }}){.code = 0};
    {%- when None %}
    {{ method.c_return_type }} result = {{ method.failure_value }};
    {%- endmatch %}
