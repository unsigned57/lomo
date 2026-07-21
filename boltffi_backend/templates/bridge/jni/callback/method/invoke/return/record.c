    {{ method.c_return_type }} result = {0};
    if (!boltffi_jni_read_record(env, __boltffi_return_array, (uintptr_t)sizeof(result), &result)) {
        (*env)->DeleteLocalRef(env, __boltffi_return_array);
        goto __boltffi_fail;
    }
    (*env)->DeleteLocalRef(env, __boltffi_return_array);
