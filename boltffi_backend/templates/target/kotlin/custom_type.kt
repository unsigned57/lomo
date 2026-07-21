{%- if custom_type.has_representation_alias() -%}
typealias {{ custom_type.name() }} = {{ custom_type.representation() }}
{%- endif %}
