static void boltffi_jni_throw_error_buffer(JNIEnv *env, FfiBuf_u8 buffer) {
    if (buffer.len > ((uintptr_t)INT32_MAX)) {
        {{ free_buffer }}(buffer);
        boltffi_jni_throw_runtime(env, "BoltFFI error buffer was too large");
        return;
    }
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)buffer.len);
    if (bytes != NULL && buffer.len != 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)buffer.len, (const jbyte *)buffer.ptr);
    }
    {{ free_buffer }}(buffer);
    if (bytes == NULL || (*env)->ExceptionCheck(env)) {
        return;
    }
    jclass exception_class = (*env)->FindClass(env, {{ error_buffer_exception_class }});
    if (exception_class == NULL) {
        (*env)->DeleteLocalRef(env, bytes);
        return;
    }
    jmethodID constructor = (*env)->GetMethodID(env, exception_class, "<init>", "([B)V");
    if (constructor == NULL) {
        (*env)->DeleteLocalRef(env, exception_class);
        (*env)->DeleteLocalRef(env, bytes);
        return;
    }
    jthrowable exception = (jthrowable)(*env)->NewObject(env, exception_class, constructor, bytes);
    if (exception != NULL) {
        (*env)->Throw(env, exception);
        (*env)->DeleteLocalRef(env, exception);
    }
    (*env)->DeleteLocalRef(env, exception_class);
    (*env)->DeleteLocalRef(env, bytes);
}
