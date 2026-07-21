JNIEXPORT void JNICALL {{ handle.release_symbol }}(JNIEnv *env, jclass cls, jlong value) {
    (void)env;
    (void)cls;
    {{ handle.release }}(value);
}
