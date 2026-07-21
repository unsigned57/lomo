static PyObject *{{ type_object }} = NULL;

static PyObject *{{ register_wrapper }}(PyObject *self, PyObject *const *args, Py_ssize_t nargs) {
    (void)self;
    if (nargs != 1) {
        PyErr_Format(PyExc_TypeError, "{{ register_method }}() takes 1 positional argument but %zd were given", nargs);
        return NULL;
    }
    if (!boltffi_python_store_registered_type(&{{ type_object }}, args[0], "{{ class_name }}")) {
        return NULL;
    }
    Py_RETURN_NONE;
}

static int {{ parser }}(PyObject *value, {{ c_type }} *out) {
{%- for field in fields %}
    PyObject *{{ field.value_name }} = NULL;
{%- endfor %}
    int parsed = 0;
    if (!boltffi_python_expect_type_instance(value, {{ type_object }}, "{{ class_name }}")) {
        return 0;
    }
{%- for field in fields %}
    {{ field.value_name }} = boltffi_python_get_record_field(value, "{{ class_name }}", "{{ field.python_name }}");
    if ({{ field.value_name }} == NULL) {
        goto cleanup;
    }
    if (!{{ field.parser }}({{ field.value_name }}, &out->{{ field.c_name }})) {
        goto cleanup;
    }
    Py_DECREF({{ field.value_name }});
    {{ field.value_name }} = NULL;
{%- endfor %}
    parsed = 1;
cleanup:
{%- for field in fields %}
    Py_XDECREF({{ field.value_name }});
{%- endfor %}
    return parsed;
}

static PyObject *{{ boxer }}({{ c_type }} value) {
    PyObject *constructor_args = PyTuple_New({{ fields.len() }});
    PyObject *field_value = NULL;
    if (constructor_args == NULL) {
        return NULL;
    }
{%- for field in fields %}
    field_value = {{ field.boxer }}(value.{{ field.c_name }});
    if (field_value == NULL) {
        Py_DECREF(constructor_args);
        return NULL;
    }
    PyTuple_SET_ITEM(constructor_args, {{ loop.index0 }}, field_value);
    field_value = NULL;
{%- endfor %}
    return boltffi_python_box_registered_record({{ type_object }}, constructor_args, "{{ class_name }}");
}

