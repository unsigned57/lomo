static void {{ handle.release }}(jlong value) {
    {{ handle.ty }} *closure = {{ handle.ref_ }}(value);
    if (closure == NULL) {
        return;
    }
    if (closure->release != NULL) {
        closure->release(closure->context);
    }
    free(closure);
}

static jlong {{ handle.new }}(JNIEnv *env, {{ handle.call_field }}, void *context, void (*release)(void *)) {
    {{ handle.ty }} *closure = ({{ handle.ty }} *)malloc(sizeof({{ handle.ty }}));
    if (closure == NULL) {
        if (release != NULL) {
            release(context);
        }
        boltffi_jni_throw_runtime(env, "failed to allocate BoltFFI closure handle");
        return 0;
    }
    closure->call = call;
    closure->context = context;
    closure->release = release;
    return (jlong)(uintptr_t)closure;
}
