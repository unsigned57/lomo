{%- if let Some(inline) = constant.inline() %}
val {{ inline.name() }}: {{ inline.ty() }} = {{ inline.value() }}
{%- endif %}
{%- if let Some(accessor) = constant.accessor() %}
val {{ accessor.name() }}{% if let Some(return_type) = accessor.returns() %}: {{ return_type }}{% endif %}
    get() {
{%- for statement in accessor.setup() %}
        {{ statement }}
{%- endfor %}
{%- if accessor.has_cleanup() %}
        try {
{%- for statement in accessor.call() %}
            {{ statement }}
{%- endfor %}
        } finally {
{%- for statement in accessor.cleanup() %}
            {{ statement }}
{%- endfor %}
        }
{%- else %}
{%- for statement in accessor.call() %}
        {{ statement }}
{%- endfor %}
{%- endif %}
    }
{%- endif %}
