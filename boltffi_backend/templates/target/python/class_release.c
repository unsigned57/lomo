static PyObject *{{ wrapper }}(PyObject *self, PyObject *const *args, Py_ssize_t nargs) {
    {{ handle_type }} handle;
    (void)self;
    if (nargs != 1) {
        PyErr_Format(PyExc_TypeError, "{{ python_name }}() takes 1 positional argument but %zd were given", nargs);
        return NULL;
    }
    if ({{ storage }} == NULL) {
        PyErr_SetString(PyExc_ImportError, "native library is not initialized");
        return NULL;
    }
    if (!{{ parser }}(args[0], &handle)) {
        return NULL;
    }
    {{ storage }}(handle);
    Py_RETURN_NONE;
}
