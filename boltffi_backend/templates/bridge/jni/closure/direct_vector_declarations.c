{%- for vector in closure.direct_vectors %}
    {{ vector.array_type }} {{ vector.name }} = NULL;
{%- endfor %}
