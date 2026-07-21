static bool boltffi_jni_read_record(JNIEnv *env, jbyteArray array, uintptr_t expected_len, void *output) {
    if (array == NULL) {
        boltffi_jni_throw_illegal_argument(env, "BoltFFI record byte array argument was null");
        return false;
    }
    jsize len = (*env)->GetArrayLength(env, array);
    if ((uintptr_t)len != expected_len) {
        boltffi_jni_throw_illegal_argument(env, "BoltFFI record byte array length did not match the C record size");
        return false;
    }
    (*env)->GetByteArrayRegion(env, array, 0, len, (jbyte *)output);
    return !(*env)->ExceptionCheck(env);
}

static jbyteArray boltffi_jni_record_to_byte_array(JNIEnv *env, const void *record, uintptr_t len) {
    if (len > (uintptr_t)INT32_MAX) {
        boltffi_jni_throw_runtime(env, "BoltFFI record too large for Java byte array");
        return NULL;
    }
    jbyteArray array = (*env)->NewByteArray(env, (jsize)len);
    if (array == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, array, 0, (jsize)len, (const jbyte *)record);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, array);
        return NULL;
    }
    return array;
}
