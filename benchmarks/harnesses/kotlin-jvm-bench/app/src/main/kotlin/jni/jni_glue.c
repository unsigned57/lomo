#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include "bench_boltffi.h"
static JavaVM* g_jvm = NULL;
static jclass g_callback_class = NULL;
static jmethodID g_callback_method = NULL;
static void init_DataProvider_callbacks(JNIEnv* env);
static void init_AsyncDataFetcher_callbacks(JNIEnv* env);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local_class = (*env)->FindClass(env, "com/example/bench_boltffi/BenchBoltFFIKt");
    if (local_class == NULL) {
        return JNI_ERR;
    }
    g_callback_class = (*env)->NewGlobalRef(env, local_class);
    g_callback_method = (*env)->GetStaticMethodID(env, g_callback_class, "boltffiFutureContinuationCallback", "(JB)V");
    if (g_callback_method == NULL) {
        return JNI_ERR;
    }
    init_DataProvider_callbacks(env);
    init_AsyncDataFetcher_callbacks(env);
    return JNI_VERSION_1_6;
}

static void boltffi_jni_continuation_callback(uint64_t handle, int8_t poll_result) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) {
            return;
        }
        attached = 1;
    } else if (get_env_result != JNI_OK) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, g_callback_class, g_callback_method, (jlong)handle, (jbyte)poll_result);
    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}
static jmethodID g_DataPoint_invoke = NULL;

static void trampoline_DataPoint(void* user_data, DataPoint p0) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) {
            return;
        }
        attached = 1;
    } else if (get_env_result != JNI_OK) {
        return;
    }
    jobject callback = (jobject)user_data;
    jobject buf_p0 = (*env)->NewDirectByteBuffer(env, (void*)&p0, sizeof(DataPoint));
    if (g_DataPoint_invoke == NULL) {
        jclass cls = (*env)->GetObjectClass(env, callback);
        g_DataPoint_invoke = (*env)->GetMethodID(env, cls, "invoke", "(Ljava/nio/ByteBuffer;)V");
    }
    (*env)->CallVoidMethod(env, callback, buf_p0);
    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1noop(JNIEnv *env, jclass cls) {
    boltffi_noop();
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1echo_1i32(JNIEnv *env, jclass cls, jint value) {
    jint _result = boltffi_echo_i32(value);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1echo_1f64(JNIEnv *env, jclass cls, jdouble value) {
    jdouble _result = boltffi_echo_f64(value);
    return _result;
}

JNIEXPORT jstring JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1echo_1string(JNIEnv *env, jclass cls, jstring value) {
    const char* _value_c = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
    FfiString _out = {0, 0, 0};
    FfiStatus _status = boltffi_echo_string((const uint8_t*)_value_c, value ? strlen(_value_c) : 0, &_out);
    if (value) (*env)->ReleaseStringUTFChars(env, value, _value_c);
    if (_status.code != 0) return NULL;
    char* _tmp = (char*)malloc(_out.len + 1);
    if (!_tmp) { boltffi_free_string(_out); return NULL; }
    memcpy(_tmp, _out.ptr, _out.len);
    _tmp[_out.len] = '\0';
    jstring _result = (*env)->NewStringUTF(env, _tmp);
    free(_tmp);
    boltffi_free_string(_out);
    return _result;
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1add(JNIEnv *env, jclass cls, jint a, jint b) {
    jint _result = boltffi_add(a, b);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1multiply(JNIEnv *env, jclass cls, jdouble a, jdouble b) {
    jdouble _result = boltffi_multiply(a, b);
    return _result;
}

JNIEXPORT jstring JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1string(JNIEnv *env, jclass cls, jint size) {
    FfiString _out = {0, 0, 0};
    FfiStatus _status = boltffi_generate_string(size, &_out);
    if (_status.code != 0) return NULL;
    char* _tmp = (char*)malloc(_out.len + 1);
    if (!_tmp) { boltffi_free_string(_out); return NULL; }
    memcpy(_tmp, _out.ptr, _out.len);
    _tmp[_out.len] = '\0';
    jstring _result = (*env)->NewStringUTF(env, _tmp);
    free(_tmp);
    boltffi_free_string(_out);
    return _result;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1locations(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_Location _ffi_buf = boltffi_generate_locations(count);
    size_t _byte_size = _ffi_buf.len * 40;
    void* _buf = malloc(_byte_size);
    if (!_buf) {
        boltffi_free_buf_Location(_ffi_buf);
        return NULL;
    }
    memcpy(_buf, _ffi_buf.ptr, _byte_size);
    boltffi_free_buf_Location(_ffi_buf);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)_byte_size);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1process_1locations(JNIEnv *env, jclass cls, jobject locations) {
    jsize _locations_size = (*env)->GetArrayLength(env, locations);
    jbyte* _locations_ptr = (*env)->GetByteArrayElements(env, locations, NULL);
    uintptr_t _locations_len = (uintptr_t)(_locations_size / 40);
    jint _result = boltffi_process_locations((const Location*)_locations_ptr, (uintptr_t)_locations_len);
    (*env)->ReleaseByteArrayElements(env, locations, _locations_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1ratings(JNIEnv *env, jclass cls, jobject locations) {
    jsize _locations_size = (*env)->GetArrayLength(env, locations);
    jbyte* _locations_ptr = (*env)->GetByteArrayElements(env, locations, NULL);
    uintptr_t _locations_len = (uintptr_t)(_locations_size / 40);
    jdouble _result = boltffi_sum_ratings((const Location*)_locations_ptr, (uintptr_t)_locations_len);
    (*env)->ReleaseByteArrayElements(env, locations, _locations_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jbyteArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1bytes(JNIEnv *env, jclass cls, jint size) {
    FfiBuf_u8 _ffi_buf = boltffi_generate_bytes(size);
    jbyteArray _result = (*env)->NewByteArray(env, (jsize)_ffi_buf.len);
    if (!_result) {
        boltffi_free_buf_u8(_ffi_buf);
        return NULL;
    }
    uint8_t* _dst = (uint8_t*)(*env)->GetPrimitiveArrayCritical(env, _result, NULL);
    memcpy(_dst, _ffi_buf.ptr, _ffi_buf.len * sizeof(uint8_t));
    (*env)->ReleasePrimitiveArrayCritical(env, _result, _dst, 0);
    boltffi_free_buf_u8(_ffi_buf);
    return _result;
}

JNIEXPORT jintArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1i32_1vec(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_i32 _ffi_buf = boltffi_generate_i32_vec(count);
    jintArray _result = (*env)->NewIntArray(env, (jsize)_ffi_buf.len);
    if (!_result) {
        boltffi_free_buf_i32(_ffi_buf);
        return NULL;
    }
    int32_t* _dst = (int32_t*)(*env)->GetPrimitiveArrayCritical(env, _result, NULL);
    memcpy(_dst, _ffi_buf.ptr, _ffi_buf.len * sizeof(int32_t));
    (*env)->ReleasePrimitiveArrayCritical(env, _result, _dst, 0);
    boltffi_free_buf_i32(_ffi_buf);
    return _result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1i32_1vec(JNIEnv *env, jclass cls, jintArray values) {
    uintptr_t _values_len = (uintptr_t)(*env)->GetArrayLength(env, values);
    int32_t* _values_ptr = (int32_t*)(*env)->GetPrimitiveArrayCritical(env, values, NULL);
    jlong _result = boltffi_sum_i32_vec((const int32_t*)_values_ptr, (uintptr_t)_values_len);
    (*env)->ReleasePrimitiveArrayCritical(env, values, _values_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1divide(JNIEnv *env, jclass cls, jint a, jint b) {
    int32_t _out = 0;
    uint8_t _out_err[24];
    memset(_out_err, 0, 24);
    FfiStatus _status = boltffi_divide(a, b, &_out, (void*)_out_err);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        char* _err_tmp = (char*)malloc(_err_str->len + 1);
        if (_err_tmp) {
            memcpy(_err_tmp, _err_str->ptr, _err_str->len);
            _err_tmp[_err_str->len] = '\0';
            (*env)->ThrowNew(env, _exc_cls, _err_tmp);
            free(_err_tmp);
        } else {
            (*env)->ThrowNew(env, _exc_cls, "FFI error");
        }
        boltffi_free_string(*_err_str);
        return 0;
    }
    return _out;
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1parse_1int(JNIEnv *env, jclass cls, jstring s) {
    const char* _s_c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
    int32_t _out = 0;
    uint8_t _out_err[24];
    memset(_out_err, 0, 24);
    FfiStatus _status = boltffi_parse_int((const uint8_t*)_s_c, s ? strlen(_s_c) : 0, &_out, (void*)_out_err);
    if (s) (*env)->ReleaseStringUTFChars(env, s, _s_c);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        char* _err_tmp = (char*)malloc(_err_str->len + 1);
        if (_err_tmp) {
            memcpy(_err_tmp, _err_str->ptr, _err_str->len);
            _err_tmp[_err_str->len] = '\0';
            (*env)->ThrowNew(env, _exc_cls, _err_tmp);
            free(_err_tmp);
        } else {
            (*env)->ThrowNew(env, _exc_cls, "FFI error");
        }
        boltffi_free_string(*_err_str);
        return 0;
    }
    return _out;
}

JNIEXPORT jstring JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1validate_1name(JNIEnv *env, jclass cls, jstring name) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    FfiString _out = {0, 0, 0};
    uint8_t _out_err[24];
    memset(_out_err, 0, 24);
    FfiStatus _status = boltffi_validate_name((const uint8_t*)_name_c, name ? strlen(_name_c) : 0, &_out, (void*)_out_err);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        char* _err_tmp = (char*)malloc(_err_str->len + 1);
        if (_err_tmp) {
            memcpy(_err_tmp, _err_str->ptr, _err_str->len);
            _err_tmp[_err_str->len] = '\0';
            (*env)->ThrowNew(env, _exc_cls, _err_tmp);
            free(_err_tmp);
        } else {
            (*env)->ThrowNew(env, _exc_cls, "FFI error");
        }
        boltffi_free_string(*_err_str);
        return NULL;
    }
    char* _tmp = (char*)malloc(_out.len + 1);
    if (!_tmp) { boltffi_free_string(_out); return NULL; }
    memcpy(_tmp, _out.ptr, _out.len);
    _tmp[_out.len] = '\0';
    jstring _result = (*env)->NewStringUTF(env, _tmp);
    free(_tmp);
    boltffi_free_string(_out);
    return _result;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1location(JNIEnv *env, jclass cls, jint id) {
    uint8_t _out[40];
    memset(_out, 0, 40);
    uint8_t _out_err[24];
    memset(_out_err, 0, 24);
    FfiStatus _status = boltffi_fetch_location(id, (void*)_out, (void*)_out_err);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        char* _err_tmp = (char*)malloc(_err_str->len + 1);
        if (_err_tmp) {
            memcpy(_err_tmp, _err_str->ptr, _err_str->len);
            _err_tmp[_err_str->len] = '\0';
            (*env)->ThrowNew(env, _exc_cls, _err_tmp);
            free(_err_tmp);
        } else {
            (*env)->ThrowNew(env, _exc_cls, "FFI error");
        }
        boltffi_free_string(*_err_str);
        return NULL;
    }
    void* _buf = malloc(40);
    if (!_buf) return NULL;
    memcpy(_buf, _out, 40);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)40);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1direction(JNIEnv *env, jclass cls, jint degrees) {
    int32_t _out = 0;
    uint8_t _out_err[24];
    memset(_out_err, 0, 24);
    FfiStatus _status = boltffi_get_direction(degrees, &_out, (void*)_out_err);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        char* _err_tmp = (char*)malloc(_err_str->len + 1);
        if (_err_tmp) {
            memcpy(_err_tmp, _err_str->ptr, _err_str->len);
            _err_tmp[_err_str->len] = '\0';
            (*env)->ThrowNew(env, _exc_cls, _err_tmp);
            free(_err_tmp);
        } else {
            (*env)->ThrowNew(env, _exc_cls, "FFI error");
        }
        boltffi_free_string(*_err_str);
        return 0;
    }
    return _out;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1process_1value(JNIEnv *env, jclass cls, jint value) {
    uint8_t _out[12];
    memset(_out, 0, 12);
    uint8_t _out_err[24];
    memset(_out_err, 0, 24);
    FfiStatus _status = boltffi_try_process_value(value, (void*)_out, (void*)_out_err);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        char* _err_tmp = (char*)malloc(_err_str->len + 1);
        if (_err_tmp) {
            memcpy(_err_tmp, _err_str->ptr, _err_str->len);
            _err_tmp[_err_str->len] = '\0';
            (*env)->ThrowNew(env, _exc_cls, _err_tmp);
            free(_err_tmp);
        } else {
            (*env)->ThrowNew(env, _exc_cls, "FFI error");
        }
        boltffi_free_string(*_err_str);
        return NULL;
    }
    void* _buf = malloc(12);
    if (!_buf) return NULL;
    memcpy(_buf, _out, 12);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)12);
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1trades(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_Trade _ffi_buf = boltffi_generate_trades(count);
    size_t _byte_size = _ffi_buf.len * 72;
    void* _buf = malloc(_byte_size);
    if (!_buf) {
        boltffi_free_buf_Trade(_ffi_buf);
        return NULL;
    }
    memcpy(_buf, _ffi_buf.ptr, _byte_size);
    boltffi_free_buf_Trade(_ffi_buf);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)_byte_size);
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1particles(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_Particle _ffi_buf = boltffi_generate_particles(count);
    size_t _byte_size = _ffi_buf.len * 80;
    void* _buf = malloc(_byte_size);
    if (!_buf) {
        boltffi_free_buf_Particle(_ffi_buf);
        return NULL;
    }
    memcpy(_buf, _ffi_buf.ptr, _byte_size);
    boltffi_free_buf_Particle(_ffi_buf);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)_byte_size);
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1sensor_1readings(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_SensorReading _ffi_buf = boltffi_generate_sensor_readings(count);
    size_t _byte_size = _ffi_buf.len * 64;
    void* _buf = malloc(_byte_size);
    if (!_buf) {
        boltffi_free_buf_SensorReading(_ffi_buf);
        return NULL;
    }
    memcpy(_buf, _ffi_buf.ptr, _byte_size);
    boltffi_free_buf_SensorReading(_ffi_buf);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)_byte_size);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1trade_1volumes(JNIEnv *env, jclass cls, jobject trades) {
    jsize _trades_size = (*env)->GetArrayLength(env, trades);
    jbyte* _trades_ptr = (*env)->GetByteArrayElements(env, trades, NULL);
    uintptr_t _trades_len = (uintptr_t)(_trades_size / 72);
    jlong _result = boltffi_sum_trade_volumes((const Trade*)_trades_ptr, (uintptr_t)_trades_len);
    (*env)->ReleaseByteArrayElements(env, trades, _trades_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1particle_1masses(JNIEnv *env, jclass cls, jobject particles) {
    jsize _particles_size = (*env)->GetArrayLength(env, particles);
    jbyte* _particles_ptr = (*env)->GetByteArrayElements(env, particles, NULL);
    uintptr_t _particles_len = (uintptr_t)(_particles_size / 80);
    jdouble _result = boltffi_sum_particle_masses((const Particle*)_particles_ptr, (uintptr_t)_particles_len);
    (*env)->ReleaseByteArrayElements(env, particles, _particles_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1avg_1sensor_1temperature(JNIEnv *env, jclass cls, jobject readings) {
    jsize _readings_size = (*env)->GetArrayLength(env, readings);
    jbyte* _readings_ptr = (*env)->GetByteArrayElements(env, readings, NULL);
    uintptr_t _readings_len = (uintptr_t)(_readings_size / 64);
    jdouble _result = boltffi_avg_sensor_temperature((const SensorReading*)_readings_ptr, (uintptr_t)_readings_len);
    (*env)->ReleaseByteArrayElements(env, readings, _readings_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jdoubleArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1f64_1vec(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_f64 _ffi_buf = boltffi_generate_f64_vec(count);
    jdoubleArray _result = (*env)->NewDoubleArray(env, (jsize)_ffi_buf.len);
    if (!_result) {
        boltffi_free_buf_f64(_ffi_buf);
        return NULL;
    }
    double* _dst = (double*)(*env)->GetPrimitiveArrayCritical(env, _result, NULL);
    memcpy(_dst, _ffi_buf.ptr, _ffi_buf.len * sizeof(double));
    (*env)->ReleasePrimitiveArrayCritical(env, _result, _dst, 0);
    boltffi_free_buf_f64(_ffi_buf);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1f64_1vec(JNIEnv *env, jclass cls, jdoubleArray values) {
    uintptr_t _values_len = (uintptr_t)(*env)->GetArrayLength(env, values);
    double* _values_ptr = (double*)(*env)->GetPrimitiveArrayCritical(env, values, NULL);
    jdouble _result = boltffi_sum_f64_vec((const double*)_values_ptr, (uintptr_t)_values_len);
    (*env)->ReleasePrimitiveArrayCritical(env, values, _values_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1opposite_1direction(JNIEnv *env, jclass cls, jint dir) {
    jint _result = boltffi_opposite_direction(dir);
    return _result;
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1direction_1to_1degrees(JNIEnv *env, jclass cls, jint dir) {
    jint _result = boltffi_direction_to_degrees(dir);
    return _result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1even(JNIEnv *env, jclass cls, jint value) {
    FfiOption_i32 _result = boltffi_find_even(value);
    return ((jlong)_result.isSome << 32) | ((jlong)(uint32_t)*(int32_t*)&_result.value);
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1positive_1i64(JNIEnv *env, jclass cls, jlong value) {
    FfiOption_i64 _result = boltffi_find_positive_i64(value);
    if (!_result.isSome) return NULL;
    jclass _box_cls = (*env)->FindClass(env, "java/lang/Long");
    jmethodID _value_of = (*env)->GetStaticMethodID(env, _box_cls, "valueOf", "(J)Ljava/lang/Long;");
    return (*env)->CallStaticObjectMethod(env, _box_cls, _value_of, (jlong)_result.value);
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1positive_1f64(JNIEnv *env, jclass cls, jdouble value) {
    FfiOption_f64 _result = boltffi_find_positive_f64(value);
    if (!_result.isSome) return NULL;
    jclass _box_cls = (*env)->FindClass(env, "java/lang/Double");
    jmethodID _value_of = (*env)->GetStaticMethodID(env, _box_cls, "valueOf", "(D)Ljava/lang/Double;");
    return (*env)->CallStaticObjectMethod(env, _box_cls, _value_of, (jdouble)_result.value);
}

JNIEXPORT jstring JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1name(JNIEnv *env, jclass cls, jint id) {
    FfiOption_FfiString _result = boltffi_find_name(id);
    if (!_result.isSome) return NULL;
    FfiString _out_value = _result.value;
    char* _tmp = (char*)malloc(_out_value.len + 1);
    if (!_tmp) { boltffi_free_string(_out_value); return NULL; }
    memcpy(_tmp, _out_value.ptr, _out_value.len);
    _tmp[_out_value.len] = '\0';
    jstring _str_result = (*env)->NewStringUTF(env, _tmp);
    free(_tmp);
    boltffi_free_string(_out_value);
    return _str_result;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1location(JNIEnv *env, jclass cls, jint id) {
    FfiOption_Location _result = boltffi_find_location(id);
    if (!_result.isSome) return NULL;
    void* _buf = malloc(40);
    if (!_buf) return NULL;
    memcpy(_buf, &_result.value, 40);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)40);
}

JNIEXPORT jintArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1numbers(JNIEnv *env, jclass cls, jint count) {
    FfiOption_FfiBuf_i32 _opt = boltffi_find_numbers(count);
    if (!_opt.isSome) {
        return NULL;
    }
    jintArray _result = (*env)->NewIntArray(env, (jsize)_opt.value.len);
    if (!_result) {
        boltffi_free_buf_i32(_opt.value);
        return NULL;
    }
    int32_t* _dst = (int32_t*)(*env)->GetPrimitiveArrayCritical(env, _result, NULL);
    memcpy(_dst, _opt.value.ptr, _opt.value.len * sizeof(int32_t));
    (*env)->ReleasePrimitiveArrayCritical(env, _result, _dst, 0);
    boltffi_free_buf_i32(_opt.value);
    return _result;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1locations(JNIEnv *env, jclass cls, jint count) {
    FfiOption_FfiBuf_Location _opt = boltffi_find_locations(count);
    if (!_opt.isSome) {
        return NULL;
    }
    size_t _byte_size = _opt.value.len * 40;
    void* _buf = malloc(_byte_size);
    if (!_buf) {
        boltffi_free_buf_Location(_opt.value);
        return NULL;
    }
    memcpy(_buf, _opt.value.ptr, _byte_size);
    boltffi_free_buf_Location(_opt.value);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)_byte_size);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1direction(JNIEnv *env, jclass cls, jint id) {
    FfiOption_Direction _result = boltffi_find_direction(id);
    return _result.isSome ? (jint)_result.value : (jint)-1;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1api_1result(JNIEnv *env, jclass cls, jint code) {
    FfiOption_ApiResult _result = boltffi_find_api_result(code);
    if (!_result.isSome) return NULL;
    void* _buf = malloc(12);
    if (!_buf) return NULL;
    memcpy(_buf, &_result.value, 12);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)12);
}

JNIEXPORT jobjectArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1names(JNIEnv *env, jclass cls, jint count) {
    FfiOption_FfiBuf_FfiString _opt = boltffi_find_names(count);
    if (!_opt.isSome) {
        return NULL;
    }
    jclass _str_cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray _result = (*env)->NewObjectArray(env, (jsize)_opt.value.len, _str_cls, NULL);
    FfiString* _strings = (FfiString*)_opt.value.ptr;
    for (size_t i = 0; i < _opt.value.len; i++) {
        char* _tmp = (char*)malloc(_strings[i].len + 1);
        if (_tmp) {
            memcpy(_tmp, _strings[i].ptr, _strings[i].len);
            _tmp[_strings[i].len] = '\0';
            jstring _jstr = (*env)->NewStringUTF(env, _tmp);
            (*env)->SetObjectArrayElement(env, _result, (jsize)i, _jstr);
            free(_tmp);
        }
    }
    boltffi_free_buf_FfiString(_opt.value);
    return _result;
}

JNIEXPORT jintArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1directions(JNIEnv *env, jclass cls, jint count) {
    FfiOption_FfiBuf_Direction _opt = boltffi_find_directions(count);
    if (!_opt.isSome) {
        return NULL;
    }
    jintArray _result = (*env)->NewIntArray(env, (jsize)_opt.value.len);
    if (!_result) {
        boltffi_free_buf_Direction(_opt.value);
        return NULL;
    }
    int32_t* _dst = (int32_t*)(*env)->GetPrimitiveArrayCritical(env, _result, NULL);
    memcpy(_dst, _opt.value.ptr, _opt.value.len * sizeof(int32_t));
    (*env)->ReleasePrimitiveArrayCritical(env, _result, _dst, 0);
    boltffi_free_buf_Direction(_opt.value);
    return _result;
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1process_1value(JNIEnv *env, jclass cls, jint value) {
    ApiResult _enum_result = boltffi_process_value(value);
    void* _buf = malloc(12);
    if (!_buf) return NULL;
    memcpy(_buf, &_enum_result, 12);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)12);
}

JNIEXPORT jboolean JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1api_1result_1is_1success(JNIEnv *env, jclass cls, jobject result) {
    jbyte* _result_ptr = (*env)->GetByteArrayElements(env, result, NULL);
    jboolean _result = boltffi_api_result_is_success(*(ApiResult*)_result_ptr);
    (*env)->ReleaseByteArrayElements(env, result, _result_ptr, JNI_ABORT);
    return _result;
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute(JNIEnv *env, jclass cls, jint value) {
    int32_t _out = 0;
    uint8_t _out_err[12];
    memset(_out_err, 0, 12);
    FfiStatus _status = boltffi_try_compute(value, &_out, (void*)_out_err);
    if (_status.code != 0) {
        void* _err_buf = malloc(12);
        if (_err_buf) {
            memcpy(_err_buf, _out_err, 12);
            jclass _exc_cls = (*env)->FindClass(env, "com/example/bench_boltffi/BoltFFIException");
            if (_exc_cls) {
                jmethodID _exc_ctor = (*env)->GetMethodID(env, _exc_cls, "<init>", "(Ljava/nio/ByteBuffer;)V");
                if (_exc_ctor) {
                    jobject _err_bb = (*env)->NewDirectByteBuffer(env, _err_buf, (jlong)12);
                    jobject _exc = (*env)->NewObject(env, _exc_cls, _exc_ctor, _err_bb);
                    if (_exc) (*env)->Throw(env, (jthrowable)_exc);
                } else {
                    free(_err_buf);
                }
            } else {
                free(_err_buf);
            }
        }
        return 0;
    }
    return _out;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1counter_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_counter_new();
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1counter_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_counter_free((void*)handle);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1counter_1set(JNIEnv *env, jclass cls, jlong handle, jlong value) {
    if (handle == 0) return;
    boltffi_counter_set((void*)handle, value);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1counter_1increment(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return;
    boltffi_counter_increment((void*)handle);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1counter_1get(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jlong _result = boltffi_counter_get((void*)handle);
    return _result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_data_store_new();
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_data_store_free((void*)handle);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1len(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jlong _result = boltffi_data_store_len((void*)handle);
    return _result;
}

JNIEXPORT jboolean JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1is_1empty(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jboolean _result = boltffi_data_store_is_empty((void*)handle);
    return _result;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1foreach(JNIEnv *env, jclass cls, jlong handle, jobject callback) {
    if (handle == 0) return;
    jobject _callback_ref = (*env)->NewGlobalRef(env, (jobject)callback);
    boltffi_data_store_foreach((void*)handle, trampoline_DataPoint, (void*)_callback_ref);
    (*env)->DeleteGlobalRef(env, _callback_ref);
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1sum(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jdouble _result = boltffi_data_store_sum((void*)handle);
    return _result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1sum(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    RustFutureHandle _handle = boltffi_data_store_async_sum((void*)handle);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1sum_1poll(JNIEnv *env, jclass cls, jlong handle, jlong future, jlong contHandle) {
    boltffi_data_store_async_sum_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1sum_1complete(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    FfiError _out_err = {0};
    FfiStatus _status;
    double _out = boltffi_data_store_async_sum_complete((RustFutureHandle)future, &_status, &_out_err);
    if (_status.code != 0) {
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        if (_exc_cls && _out_err.message.ptr) {
            char* _err_tmp = (char*)malloc(_out_err.message.len + 1);
            if (_err_tmp) {
                memcpy(_err_tmp, _out_err.message.ptr, _out_err.message.len);
                _err_tmp[_out_err.message.len] = '\0';
                (*env)->ThrowNew(env, _exc_cls, _err_tmp);
                free(_err_tmp);
            }
            boltffi_free_string(_out_err.message);
        }
        return 0;
    }
    return (jdouble)_out;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1sum_1cancel(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    boltffi_data_store_async_sum_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1sum_1free(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    boltffi_data_store_async_sum_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1len(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    RustFutureHandle _handle = boltffi_data_store_async_len((void*)handle);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1len_1poll(JNIEnv *env, jclass cls, jlong handle, jlong future, jlong contHandle) {
    boltffi_data_store_async_len_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1len_1complete(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    FfiError _out_err = {0};
    FfiStatus _status;
    uintptr_t _out = boltffi_data_store_async_len_complete((RustFutureHandle)future, &_status, &_out_err);
    if (_status.code != 0) {
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        if (_exc_cls && _out_err.message.ptr) {
            char* _err_tmp = (char*)malloc(_out_err.message.len + 1);
            if (_err_tmp) {
                memcpy(_err_tmp, _out_err.message.ptr, _out_err.message.len);
                _err_tmp[_out_err.message.len] = '\0';
                (*env)->ThrowNew(env, _exc_cls, _err_tmp);
                free(_err_tmp);
            }
            boltffi_free_string(_out_err.message);
        }
        return 0;
    }
    return (jlong)_out;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1len_1cancel(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    boltffi_data_store_async_len_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1len_1free(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    boltffi_data_store_async_len_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1accumulator_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_accumulator_new();
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1accumulator_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_accumulator_free((void*)handle);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1accumulator_1add(JNIEnv *env, jclass cls, jlong handle, jlong amount) {
    if (handle == 0) return;
    boltffi_accumulator_add((void*)handle, amount);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1accumulator_1get(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jlong _result = boltffi_accumulator_get((void*)handle);
    return _result;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1accumulator_1reset(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return;
    boltffi_accumulator_reset((void*)handle);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sensor_1monitor_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_sensor_monitor_new();
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sensor_1monitor_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_sensor_monitor_free((void*)handle);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sensor_1monitor_1emit_1reading(JNIEnv *env, jclass cls, jlong handle, jint sensor_id, jlong timestamp_ms, jdouble value) {
    if (handle == 0) return;
    boltffi_sensor_monitor_emit_reading((void*)handle, sensor_id, timestamp_ms, value);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sensor_1monitor_1subscriber_1count(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jlong _result = boltffi_sensor_monitor_subscriber_count((void*)handle);
    return _result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_data_consumer_new();
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_data_consumer_free((void*)handle);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1compute_1sum(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    jlong _result = boltffi_data_consumer_compute_sum((void*)handle);
    return _result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1add(JNIEnv *env, jclass cls, jint a, jint b) {
    RustFutureHandle _handle = boltffi_async_add(a, b);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1add_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_async_add_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1add_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    int32_t _result = boltffi_async_add_complete((RustFutureHandle)future, &_status);
    return (jint)_result;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1add_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_add_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1add_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_add_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1compute_1heavy(JNIEnv *env, jclass cls, jint input) {
    RustFutureHandle _handle = boltffi_compute_heavy(input);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1compute_1heavy_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_compute_heavy_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1compute_1heavy_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    int32_t _result = boltffi_compute_heavy_complete((RustFutureHandle)future, &_status);
    return (jint)_result;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1compute_1heavy_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_compute_heavy_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1compute_1heavy_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_compute_heavy_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute_1async(JNIEnv *env, jclass cls, jint value) {
    RustFutureHandle _handle = boltffi_try_compute_async(value);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute_1async_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_try_compute_async_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute_1async_1complete(JNIEnv *env, jclass cls, jlong future) {
    uint8_t _out_err[12];
    memset(_out_err, 0, 12);
    FfiStatus _status;
    int32_t _out = boltffi_try_compute_async_complete((RustFutureHandle)future, &_status, (void*)&_out_err);
    if (_status.code != 0) {
        void* _err_buf = malloc(12);
        if (_err_buf) {
            memcpy(_err_buf, _out_err, 12);
            jclass _exc_cls = (*env)->FindClass(env, "com/example/bench_boltffi/BoltFFIException");
            if (_exc_cls) {
                jmethodID _exc_ctor = (*env)->GetMethodID(env, _exc_cls, "<init>", "(Ljava/nio/ByteBuffer;)V");
                if (_exc_ctor) {
                    jobject _err_bb = (*env)->NewDirectByteBuffer(env, _err_buf, (jlong)12);
                    jobject _exc = (*env)->NewObject(env, _exc_cls, _exc_ctor, _err_bb);
                    if (_exc) (*env)->Throw(env, (jthrowable)_exc);
                } else {
                    free(_err_buf);
                }
            } else {
                free(_err_buf);
            }
        }
        return 0;
    }
    return (jint)_out;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute_1async_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_try_compute_async_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute_1async_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_try_compute_async_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1data(JNIEnv *env, jclass cls, jint id) {
    RustFutureHandle _handle = boltffi_fetch_data(id);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1data_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_fetch_data_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1data_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiError _out_err = {0};
    FfiStatus _status;
    int32_t _out = boltffi_fetch_data_complete((RustFutureHandle)future, &_status, (void*)&_out_err);
    if (_status.code != 0) {
        FfiString* _err_str = (FfiString*)&_out_err;
        jclass _exc_cls = (*env)->FindClass(env, "java/lang/Exception");
        if (_exc_cls && _err_str->ptr) {
            char* _err_tmp = (char*)malloc(_err_str->len + 1);
            if (_err_tmp) {
                memcpy(_err_tmp, _err_str->ptr, _err_str->len);
                _err_tmp[_err_str->len] = '\0';
                (*env)->ThrowNew(env, _exc_cls, _err_tmp);
                free(_err_tmp);
            }
            boltffi_free_string(*_err_str);
        }
        return 0;
    }
    return (jint)_out;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1data_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_fetch_data_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1data_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_fetch_data_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1make_1string(JNIEnv *env, jclass cls, jint value) {
    RustFutureHandle _handle = boltffi_async_make_string(value);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1make_1string_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_async_make_string_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jstring JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1make_1string_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    FfiString _out = boltffi_async_make_string_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _out.ptr == NULL) return NULL;
    char* _tmp = (char*)malloc(_out.len + 1);
    if (!_tmp) { boltffi_free_string(_out); return NULL; }
    memcpy(_tmp, _out.ptr, _out.len);
    _tmp[_out.len] = '\0';
    jstring _result = (*env)->NewStringUTF(env, _tmp);
    free(_tmp);
    boltffi_free_string(_out);
    return _result;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1make_1string_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_make_string_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1make_1string_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_make_string_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1fetch_1point(JNIEnv *env, jclass cls, jdouble x, jdouble y) {
    RustFutureHandle _handle = boltffi_async_fetch_point(x, y);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1fetch_1point_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_async_fetch_point_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1fetch_1point_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    DataPoint _out = boltffi_async_fetch_point_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0) return NULL;
    void* _buf = malloc(24);
    if (!_buf) return NULL;
    memcpy(_buf, &_out, 24);
    return (*env)->NewDirectByteBuffer(env, _buf, (jlong)24);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1fetch_1point_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_fetch_point_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1fetch_1point_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_fetch_point_free((RustFutureHandle)future);
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers(JNIEnv *env, jclass cls, jint count) {
    RustFutureHandle _handle = boltffi_async_get_numbers(count);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1poll(JNIEnv *env, jclass cls, jlong future, jlong contHandle) {
    boltffi_async_get_numbers_poll((RustFutureHandle)future, (uint64_t)contHandle, boltffi_jni_continuation_callback);
}

JNIEXPORT jintArray JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    FfiBuf_i32 _buf = boltffi_async_get_numbers_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    jintArray _result = (*env)->NewIntArray(env, (jsize)_buf.len);
    if (_result) {
        (*env)->SetIntArrayRegion(env, _result, 0, (jsize)_buf.len, (const jint*)_buf.ptr);
    }
    boltffi_free_buf_i32(_buf);
    return _result;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_get_numbers_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_get_numbers_free((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1free_1native_1buffer(JNIEnv *env, jclass cls, jobject buffer) {
    if (buffer) {
        void* ptr = (*env)->GetDirectBufferAddress(env, buffer);
        if (ptr) free(ptr);
    }
}

static jclass g_DataProvider_callbacks_class = NULL;
static jmethodID g_DataProvider_free_method = NULL;
static jmethodID g_DataProvider_clone_method = NULL;
static jmethodID g_DataProvider_get_count_method = NULL;

static void DataProvider_vtable_free(uint64_t handle) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (get_env_result != JNI_OK) return;
    (*env)->CallStaticVoidMethod(env, g_DataProvider_callbacks_class, g_DataProvider_free_method, (jlong)handle);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static uint64_t DataProvider_vtable_clone(uint64_t handle) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (get_env_result != JNI_OK) return 0;
    jlong result = (*env)->CallStaticLongMethod(env, g_DataProvider_callbacks_class, g_DataProvider_clone_method, (jlong)handle);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    return (uint64_t)result;
}

static void DataProvider_vtable_get_count(uint64_t handle, uint32_t* out, FfiStatus* status) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) {
            status->code = 1;
            return;
        }
        attached = 1;
    } else if (get_env_result != JNI_OK) {
        status->code = 1;
        return;
    }
    jint _result = (*env)->CallStaticIntMethod(env, g_DataProvider_callbacks_class, g_DataProvider_get_count_method, (jlong)handle);
    *out = (uint32_t)_result;
    status->code = 0;
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static DataProviderVTable g_DataProvider_vtable = {
    .free = DataProvider_vtable_free,
    .clone = DataProvider_vtable_clone,
    .get_count = DataProvider_vtable_get_count,
};

static void init_DataProvider_callbacks(JNIEnv* env) {
    jclass local_class = (*env)->FindClass(env, "com/example/bench_boltffi/DataProviderCallbacks");
    if (local_class == NULL) return;
    g_DataProvider_callbacks_class = (*env)->NewGlobalRef(env, local_class);
    g_DataProvider_free_method = (*env)->GetStaticMethodID(env, g_DataProvider_callbacks_class, "free", "(J)V");
    g_DataProvider_clone_method = (*env)->GetStaticMethodID(env, g_DataProvider_callbacks_class, "clone", "(J)J");
    g_DataProvider_get_count_method = (*env)->GetStaticMethodID(env, g_DataProvider_callbacks_class, "get_count", "(J)I");
    boltffi_register_data_provider_vtable(&g_DataProvider_vtable);
}

static jclass g_AsyncDataFetcher_callbacks_class = NULL;
static jmethodID g_AsyncDataFetcher_free_method = NULL;
static jmethodID g_AsyncDataFetcher_clone_method = NULL;
static jmethodID g_AsyncDataFetcher_fetch_value_method = NULL;

static void AsyncDataFetcher_vtable_free(uint64_t handle) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (get_env_result != JNI_OK) return;
    (*env)->CallStaticVoidMethod(env, g_AsyncDataFetcher_callbacks_class, g_AsyncDataFetcher_free_method, (jlong)handle);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static uint64_t AsyncDataFetcher_vtable_clone(uint64_t handle) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (get_env_result != JNI_OK) return 0;
    jlong result = (*env)->CallStaticLongMethod(env, g_AsyncDataFetcher_callbacks_class, g_AsyncDataFetcher_clone_method, (jlong)handle);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    return (uint64_t)result;
}

static void AsyncDataFetcher_vtable_fetch_value(uint64_t handle, uint32_t key, void (*callback)(uint64_t, uint64_t, FfiStatus), uint64_t callback_data) {
    JNIEnv* env;
    int attached = 0;
    jint get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (get_env_result != JNI_OK) return;
    (*env)->CallStaticVoidMethod(env, g_AsyncDataFetcher_callbacks_class, g_AsyncDataFetcher_fetch_value_method, (jlong)handle, (jint)key, (jlong)callback, (jlong)callback_data);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static AsyncDataFetcherVTable g_AsyncDataFetcher_vtable = {
    .free = AsyncDataFetcher_vtable_free,
    .clone = AsyncDataFetcher_vtable_clone,
    .fetch_value = AsyncDataFetcher_vtable_fetch_value,
};

static void init_AsyncDataFetcher_callbacks(JNIEnv* env) {
    jclass local_class = (*env)->FindClass(env, "com/example/bench_boltffi/AsyncDataFetcherCallbacks");
    if (local_class == NULL) return;
    g_AsyncDataFetcher_callbacks_class = (*env)->NewGlobalRef(env, local_class);
    g_AsyncDataFetcher_free_method = (*env)->GetStaticMethodID(env, g_AsyncDataFetcher_callbacks_class, "free", "(J)V");
    g_AsyncDataFetcher_clone_method = (*env)->GetStaticMethodID(env, g_AsyncDataFetcher_callbacks_class, "clone", "(J)J");
    g_AsyncDataFetcher_fetch_value_method = (*env)->GetStaticMethodID(env, g_AsyncDataFetcher_callbacks_class, "fetch_value", "(JIJJ)V");
    boltffi_register_async_data_fetcher_vtable(&g_AsyncDataFetcher_vtable);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_invokeAsyncCallbackI64(JNIEnv* env, jclass cls, jlong callback_ptr, jlong callback_data, jlong result) {
    (void)env; (void)cls;
    void (*callback)(uint64_t, int64_t, FfiStatus) = (void (*)(uint64_t, int64_t, FfiStatus))callback_ptr;
    callback((uint64_t)callback_data, (int64_t)result, (FfiStatus){.code = 0});
}