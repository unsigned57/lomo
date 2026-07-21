{%- for vector in handle.closure.handle_direct_vectors %}
    {{ vector.element_type }} *{{ vector.pointer_local }} = NULL;
    jsize {{ vector.length_local }} = 0;
{%- if let Some(stack) = vector.stack_copy %}
    {{ vector.element_type }} {{ stack.storage }}[{{ stack.max_len }}];
    bool {{ stack.needs_release }} = false;
{%- endif %}
{%- endfor %}
