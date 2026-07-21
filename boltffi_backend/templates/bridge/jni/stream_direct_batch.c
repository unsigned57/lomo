
JNIEXPORT jbyteArray JNICALL {{ batch.symbol }}(JNIEnv *env, jclass cls, jlong {{ batch.subscription }}, jlong {{ batch.max_count }}) {
    (void)cls;
    if ({{ batch.max_count }} < 0) {
        boltffi_jni_throw_illegal_argument(env, "BoltFFI stream batch size was negative");
        return NULL;
    }
    uintptr_t {{ batch.capacity }} = (uintptr_t){{ batch.max_count }};
    if ({{ batch.capacity }} != 0 && {{ batch.capacity }} > ((uintptr_t)SIZE_MAX / (uintptr_t){{ batch.item_size }})) {
        boltffi_jni_throw_runtime(env, "BoltFFI stream batch allocation is too large");
        return NULL;
    }
    uintptr_t {{ batch.byte_capacity }} = {{ batch.capacity }} * (uintptr_t){{ batch.item_size }};
    uint8_t *{{ batch.items }} = NULL;
    if ({{ batch.byte_capacity }} != 0) {
        {{ batch.items }} = (uint8_t *)malloc((size_t){{ batch.byte_capacity }});
        if ({{ batch.items }} == NULL) {
            boltffi_jni_throw_runtime(env, "failed to allocate BoltFFI stream batch buffer");
            return NULL;
        }
    }
    uintptr_t {{ batch.count }} = {{ batch.c_function }}(({{ batch.subscription_type }}){{ batch.subscription }}, ({{ batch.item_type }} *){{ batch.items }}, {{ batch.capacity }});
    if ({{ batch.count }} > {{ batch.capacity }}) {
        free({{ batch.items }});
        boltffi_jni_throw_runtime(env, "BoltFFI stream returned more items than requested");
        return NULL;
    }
    uintptr_t {{ batch.byte_len }} = {{ batch.count }} * (uintptr_t){{ batch.item_size }};
    jbyteArray {{ batch.array }} = boltffi_jni_bytes_to_byte_array(env, {{ batch.items }}, {{ batch.byte_len }});
    free({{ batch.items }});
    return {{ batch.array }};
}
