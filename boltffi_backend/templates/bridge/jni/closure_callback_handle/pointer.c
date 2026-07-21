static {{ handle.ty }} *{{ handle.ref_ }}(jlong value) {
    return value == 0 ? NULL : ({{ handle.ty }} *)(uintptr_t)value;
}
