#if defined(__ANDROID__)
static pthread_key_t boltffi_jni_env_key;
static pthread_once_t boltffi_jni_env_key_once = PTHREAD_ONCE_INIT;
static int boltffi_jni_env_key_status = 0;
static char boltffi_jni_tls_attached_marker;

static void boltffi_jni_android_env_destructor(void *value) {
    if (value != NULL && boltffi_jni_vm != NULL) {
        (*boltffi_jni_vm)->DetachCurrentThread(boltffi_jni_vm);
    }
}

static void boltffi_jni_android_env_key_init(void) {
    boltffi_jni_env_key_status =
        pthread_key_create(&boltffi_jni_env_key, boltffi_jni_android_env_destructor);
}

static jint boltffi_jni_android_attach_cached(JavaVM *vm, JNIEnv **env, int *attached) {
    *attached = 0;

    if (pthread_once(&boltffi_jni_env_key_once, boltffi_jni_android_env_key_init) != 0 ||
        boltffi_jni_env_key_status != 0) {
        jint result = boltffi_jni_attach_current_thread(vm, env);
        if (result == JNI_OK) {
            *attached = 1;
        }
        return result;
    }

    jint result = (*vm)->AttachCurrentThreadAsDaemon(vm, env, NULL);
    if (result != JNI_OK) {
        return result;
    }

    if (pthread_setspecific(boltffi_jni_env_key, &boltffi_jni_tls_attached_marker) != 0) {
        (*vm)->DetachCurrentThread(vm);
        *env = NULL;
        return JNI_ERR;
    }

    return JNI_OK;
}
#endif
