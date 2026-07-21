    if ({{ vector.length }} > (uintptr_t)INT32_MAX) {
        boltffi_jni_throw_runtime(env, "BoltFFI vector argument too large for Java array");
        goto __boltffi_fail;
    }
    {{ vector.array }} = (*env)->{{ vector.new_array }}(env, (jsize){{ vector.length }});
    if ({{ vector.array }} == NULL) {
        goto __boltffi_fail;
    }
    (*env)->{{ vector.set_region }}(env, {{ vector.array }}, 0, (jsize){{ vector.length }}, (const {{ vector.element_type }} *){{ vector.pointer }});
    if ((*env)->ExceptionCheck(env)) {
        goto __boltffi_fail;
    }
