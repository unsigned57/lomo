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

static void trampoline_DataPoint(void* user_data, const uint8_t* p0_ptr, uintptr_t p0_len) {
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
    jobject buf_p0 = (*env)->NewDirectByteBuffer(env, (void*)p0_ptr, (jlong)p0_len);
    if (g_DataPoint_invoke == NULL) {
        jclass cls = (*env)->GetObjectClass(env, callback);
        g_DataPoint_invoke = (*env)->GetMethodID(env, cls, "invoke", "(Ljava/nio/ByteBuffer;)V");
    }
    (*env)->CallVoidMethod(env, callback, g_DataPoint_invoke, buf_p0);
    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1user_1profiles(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_user_profiles(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1user_1scores(JNIEnv *env, jclass cls, jobject users) {
    jlong _users_size = (*env)->GetDirectBufferCapacity(env, users);
    uint8_t* _users_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, users);
    uintptr_t _users_len = (_users_ptr && _users_size > 0) ? (uintptr_t)_users_size : 0;
    double _result = boltffi_sum_user_scores((const uint8_t*)_users_ptr, (uintptr_t)_users_len);
    return _result;
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1count_1active_1users(JNIEnv *env, jclass cls, jobject users) {
    jlong _users_size = (*env)->GetDirectBufferCapacity(env, users);
    uint8_t* _users_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, users);
    uintptr_t _users_len = (_users_ptr && _users_size > 0) ? (uintptr_t)_users_size : 0;
    int32_t _result = boltffi_count_active_users((const uint8_t*)_users_ptr, (uintptr_t)_users_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1echo_1string(JNIEnv *env, jclass cls, jstring value) {
    const char* _value_c = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
    FfiBuf_u8 _buf = boltffi_echo_string((const uint8_t*)_value_c, value ? strlen(_value_c) : 0);
    if (value) (*env)->ReleaseStringUTFChars(env, value, _value_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1string(JNIEnv *env, jclass cls, jint size) {
    FfiBuf_u8 _buf = boltffi_generate_string(size);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1locations(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_locations(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1process_1locations(JNIEnv *env, jclass cls, jobject locations) {
    jlong _locations_size = (*env)->GetDirectBufferCapacity(env, locations);
    uint8_t* _locations_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, locations);
    uintptr_t _locations_len = (_locations_ptr && _locations_size > 0) ? (uintptr_t)_locations_size : 0;
    int32_t _result = boltffi_process_locations((const uint8_t*)_locations_ptr, (uintptr_t)_locations_len);
    return _result;
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1ratings(JNIEnv *env, jclass cls, jobject locations) {
    jlong _locations_size = (*env)->GetDirectBufferCapacity(env, locations);
    uint8_t* _locations_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, locations);
    uintptr_t _locations_len = (_locations_ptr && _locations_size > 0) ? (uintptr_t)_locations_size : 0;
    double _result = boltffi_sum_ratings((const uint8_t*)_locations_ptr, (uintptr_t)_locations_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1bytes(JNIEnv *env, jclass cls, jint size) {
    FfiBuf_u8 _buf = boltffi_generate_bytes(size);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1i32_1vec(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_i32_vec(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1i32_1vec(JNIEnv *env, jclass cls, jintArray values) {
    uintptr_t _values_len = (uintptr_t)(*env)->GetArrayLength(env, values);
    int32_t* _values_ptr = (int32_t*)(*env)->GetPrimitiveArrayCritical(env, values, NULL);
    int64_t _result = boltffi_sum_i32_vec((const int32_t*)_values_ptr, (uintptr_t)_values_len);
    (*env)->ReleasePrimitiveArrayCritical(env, values, _values_ptr, JNI_ABORT);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1divide(JNIEnv *env, jclass cls, jint a, jint b) {
    FfiBuf_u8 _buf = boltffi_divide(a, b);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1parse_1int(JNIEnv *env, jclass cls, jstring s) {
    const char* _s_c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
    FfiBuf_u8 _buf = boltffi_parse_int((const uint8_t*)_s_c, s ? strlen(_s_c) : 0);
    if (s) (*env)->ReleaseStringUTFChars(env, s, _s_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1validate_1name(JNIEnv *env, jclass cls, jstring name) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    FfiBuf_u8 _buf = boltffi_validate_name((const uint8_t*)_name_c, name ? strlen(_name_c) : 0);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1location(JNIEnv *env, jclass cls, jint id) {
    FfiBuf_u8 _buf = boltffi_fetch_location(id);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1direction(JNIEnv *env, jclass cls, jint degrees) {
    FfiBuf_u8 _buf = boltffi_get_direction(degrees);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1process_1value(JNIEnv *env, jclass cls, jint value) {
    FfiBuf_u8 _buf = boltffi_try_process_value(value);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1trades(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_trades(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1particles(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_particles(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1sensor_1readings(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_sensor_readings(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1trade_1volumes(JNIEnv *env, jclass cls, jobject trades) {
    jlong _trades_size = (*env)->GetDirectBufferCapacity(env, trades);
    uint8_t* _trades_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, trades);
    uintptr_t _trades_len = (_trades_ptr && _trades_size > 0) ? (uintptr_t)_trades_size : 0;
    int64_t _result = boltffi_sum_trade_volumes((const uint8_t*)_trades_ptr, (uintptr_t)_trades_len);
    return _result;
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1particle_1masses(JNIEnv *env, jclass cls, jobject particles) {
    jlong _particles_size = (*env)->GetDirectBufferCapacity(env, particles);
    uint8_t* _particles_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, particles);
    uintptr_t _particles_len = (_particles_ptr && _particles_size > 0) ? (uintptr_t)_particles_size : 0;
    double _result = boltffi_sum_particle_masses((const uint8_t*)_particles_ptr, (uintptr_t)_particles_len);
    return _result;
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1avg_1sensor_1temperature(JNIEnv *env, jclass cls, jobject readings) {
    jlong _readings_size = (*env)->GetDirectBufferCapacity(env, readings);
    uint8_t* _readings_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, readings);
    uintptr_t _readings_len = (_readings_ptr && _readings_size > 0) ? (uintptr_t)_readings_size : 0;
    double _result = boltffi_avg_sensor_temperature((const uint8_t*)_readings_ptr, (uintptr_t)_readings_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1generate_1f64_1vec(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_generate_f64_vec(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1sum_1f64_1vec(JNIEnv *env, jclass cls, jdoubleArray values) {
    uintptr_t _values_len = (uintptr_t)(*env)->GetArrayLength(env, values);
    double* _values_ptr = (double*)(*env)->GetPrimitiveArrayCritical(env, values, NULL);
    double _result = boltffi_sum_f64_vec((const double*)_values_ptr, (uintptr_t)_values_len);
    (*env)->ReleasePrimitiveArrayCritical(env, values, _values_ptr, JNI_ABORT);
    return _result;
}
JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1inc_1u64(JNIEnv *env, jclass cls, jlongArray value) {
    uintptr_t _value_len = (uintptr_t)(*env)->GetArrayLength(env, value);
    uint64_t* _value_ptr = (uint64_t*)(*env)->GetPrimitiveArrayCritical(env, value, NULL);
    boltffi_inc_u64((uint64_t*)_value_ptr, (uintptr_t)_value_len);
    (*env)->ReleasePrimitiveArrayCritical(env, value, _value_ptr, 0);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1opposite_1direction(JNIEnv *env, jclass cls, jobject dir) {
    jlong _dir_size = (*env)->GetDirectBufferCapacity(env, dir);
    uint8_t* _dir_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, dir);
    uintptr_t _dir_len = (_dir_ptr && _dir_size > 0) ? (uintptr_t)_dir_size : 0;
    FfiBuf_u8 _buf = boltffi_opposite_direction((const uint8_t*)_dir_ptr, (uintptr_t)_dir_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1direction_1to_1degrees(JNIEnv *env, jclass cls, jobject dir) {
    jlong _dir_size = (*env)->GetDirectBufferCapacity(env, dir);
    uint8_t* _dir_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, dir);
    uintptr_t _dir_len = (_dir_ptr && _dir_size > 0) ? (uintptr_t)_dir_size : 0;
    int32_t _result = boltffi_direction_to_degrees((const uint8_t*)_dir_ptr, (uintptr_t)_dir_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1even(JNIEnv *env, jclass cls, jint value) {
    FfiBuf_u8 _buf = boltffi_find_even(value);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1positive_1i64(JNIEnv *env, jclass cls, jlong value) {
    FfiBuf_u8 _buf = boltffi_find_positive_i64(value);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1positive_1f64(JNIEnv *env, jclass cls, jdouble value) {
    FfiBuf_u8 _buf = boltffi_find_positive_f64(value);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1name(JNIEnv *env, jclass cls, jint id) {
    FfiBuf_u8 _buf = boltffi_find_name(id);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1location(JNIEnv *env, jclass cls, jint id) {
    FfiBuf_u8 _buf = boltffi_find_location(id);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1numbers(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_find_numbers(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1locations(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_find_locations(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1direction(JNIEnv *env, jclass cls, jint id) {
    FfiBuf_u8 _buf = boltffi_find_direction(id);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1api_1result(JNIEnv *env, jclass cls, jint code) {
    FfiBuf_u8 _buf = boltffi_find_api_result(code);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1names(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_find_names(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1directions(JNIEnv *env, jclass cls, jint count) {
    FfiBuf_u8 _buf = boltffi_find_directions(count);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1process_1value(JNIEnv *env, jclass cls, jint value) {
    FfiBuf_u8 _buf = boltffi_process_value(value);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jboolean JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1api_1result_1is_1success(JNIEnv *env, jclass cls, jobject result) {
    jlong _result_size = (*env)->GetDirectBufferCapacity(env, result);
    uint8_t* _result_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, result);
    uintptr_t _result_len = (_result_ptr && _result_size > 0) ? (uintptr_t)_result_size : 0;
    bool _result = boltffi_api_result_is_success((const uint8_t*)_result_ptr, (uintptr_t)_result_len);
    return (jboolean)_result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute(JNIEnv *env, jclass cls, jint value) {
    FfiBuf_u8 _buf = boltffi_try_compute(value);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1address(JNIEnv *env, jclass cls, jstring street, jstring city, jint zip_code) {
    const char* _street_c = street ? (*env)->GetStringUTFChars(env, street, NULL) : NULL;
    const char* _city_c = city ? (*env)->GetStringUTFChars(env, city, NULL) : NULL;
    FfiBuf_u8 _buf = boltffi_create_address((const uint8_t*)_street_c, street ? strlen(_street_c) : 0, (const uint8_t*)_city_c, city ? strlen(_city_c) : 0, zip_code);
    if (street) (*env)->ReleaseStringUTFChars(env, street, _street_c);
    if (city) (*env)->ReleaseStringUTFChars(env, city, _city_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1person(JNIEnv *env, jclass cls, jstring name, jint age, jobject address) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    jlong _address_size = (*env)->GetDirectBufferCapacity(env, address);
    uint8_t* _address_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, address);
    uintptr_t _address_len = (_address_ptr && _address_size > 0) ? (uintptr_t)_address_size : 0;
    FfiBuf_u8 _buf = boltffi_create_person((const uint8_t*)_name_c, name ? strlen(_name_c) : 0, age, (const uint8_t*)_address_ptr, (uintptr_t)_address_len);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1company(JNIEnv *env, jclass cls, jstring name, jobject ceo, jobject employees, jobject headquarters) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    jlong _ceo_size = (*env)->GetDirectBufferCapacity(env, ceo);
    uint8_t* _ceo_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, ceo);
    uintptr_t _ceo_len = (_ceo_ptr && _ceo_size > 0) ? (uintptr_t)_ceo_size : 0;
    jlong _employees_size = (*env)->GetDirectBufferCapacity(env, employees);
    uint8_t* _employees_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, employees);
    uintptr_t _employees_len = (_employees_ptr && _employees_size > 0) ? (uintptr_t)_employees_size : 0;
    jlong _headquarters_size = (*env)->GetDirectBufferCapacity(env, headquarters);
    uint8_t* _headquarters_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, headquarters);
    uintptr_t _headquarters_len = (_headquarters_ptr && _headquarters_size > 0) ? (uintptr_t)_headquarters_size : 0;
    FfiBuf_u8 _buf = boltffi_create_company((const uint8_t*)_name_c, name ? strlen(_name_c) : 0, (const uint8_t*)_ceo_ptr, (uintptr_t)_ceo_len, (const uint8_t*)_employees_ptr, (uintptr_t)_employees_len, (const uint8_t*)_headquarters_ptr, (uintptr_t)_headquarters_len);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1company_1employee_1count(JNIEnv *env, jclass cls, jobject company) {
    jlong _company_size = (*env)->GetDirectBufferCapacity(env, company);
    uint8_t* _company_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, company);
    uintptr_t _company_len = (_company_ptr && _company_size > 0) ? (uintptr_t)_company_size : 0;
    int32_t _result = boltffi_get_company_employee_count((const uint8_t*)_company_ptr, (uintptr_t)_company_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1ceo_1address_1city(JNIEnv *env, jclass cls, jobject company) {
    jlong _company_size = (*env)->GetDirectBufferCapacity(env, company);
    uint8_t* _company_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, company);
    uintptr_t _company_len = (_company_ptr && _company_size > 0) ? (uintptr_t)_company_size : 0;
    FfiBuf_u8 _buf = boltffi_get_ceo_address_city((const uint8_t*)_company_ptr, (uintptr_t)_company_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1bounding_1box(JNIEnv *env, jclass cls, jdouble min_x, jdouble min_y, jdouble max_x, jdouble max_y) {
    FfiBuf_u8 _buf = boltffi_create_bounding_box(min_x, min_y, max_x, max_y);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1region(JNIEnv *env, jclass cls, jstring name, jobject bounds, jobject points) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    jlong _bounds_size = (*env)->GetDirectBufferCapacity(env, bounds);
    uint8_t* _bounds_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, bounds);
    uintptr_t _bounds_len = (_bounds_ptr && _bounds_size > 0) ? (uintptr_t)_bounds_size : 0;
    jlong _points_size = (*env)->GetDirectBufferCapacity(env, points);
    uint8_t* _points_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, points);
    uintptr_t _points_len = (_points_ptr && _points_size > 0) ? (uintptr_t)_points_size : 0;
    FfiBuf_u8 _buf = boltffi_create_region((const uint8_t*)_name_c, name ? strlen(_name_c) : 0, (const uint8_t*)_bounds_ptr, (uintptr_t)_bounds_len, (const uint8_t*)_points_ptr, (uintptr_t)_points_len);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1region_1area(JNIEnv *env, jclass cls, jobject region) {
    jlong _region_size = (*env)->GetDirectBufferCapacity(env, region);
    uint8_t* _region_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, region);
    uintptr_t _region_len = (_region_ptr && _region_size > 0) ? (uintptr_t)_region_size : 0;
    double _result = boltffi_get_region_area((const uint8_t*)_region_ptr, (uintptr_t)_region_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1task(JNIEnv *env, jclass cls, jlong id, jstring title, jobject priority, jobject status) {
    const char* _title_c = title ? (*env)->GetStringUTFChars(env, title, NULL) : NULL;
    jlong _priority_size = (*env)->GetDirectBufferCapacity(env, priority);
    uint8_t* _priority_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, priority);
    uintptr_t _priority_len = (_priority_ptr && _priority_size > 0) ? (uintptr_t)_priority_size : 0;
    jlong _status_size = (*env)->GetDirectBufferCapacity(env, status);
    uint8_t* _status_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, status);
    uintptr_t _status_len = (_status_ptr && _status_size > 0) ? (uintptr_t)_status_size : 0;
    FfiBuf_u8 _buf = boltffi_create_task(id, (const uint8_t*)_title_c, title ? strlen(_title_c) : 0, (const uint8_t*)_priority_ptr, (uintptr_t)_priority_len, (const uint8_t*)_status_ptr, (uintptr_t)_status_len);
    if (title) (*env)->ReleaseStringUTFChars(env, title, _title_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1task_1with_1assignee(JNIEnv *env, jclass cls, jlong id, jstring title, jobject priority, jobject status, jobject assignee) {
    const char* _title_c = title ? (*env)->GetStringUTFChars(env, title, NULL) : NULL;
    jlong _priority_size = (*env)->GetDirectBufferCapacity(env, priority);
    uint8_t* _priority_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, priority);
    uintptr_t _priority_len = (_priority_ptr && _priority_size > 0) ? (uintptr_t)_priority_size : 0;
    jlong _status_size = (*env)->GetDirectBufferCapacity(env, status);
    uint8_t* _status_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, status);
    uintptr_t _status_len = (_status_ptr && _status_size > 0) ? (uintptr_t)_status_size : 0;
    jlong _assignee_size = (*env)->GetDirectBufferCapacity(env, assignee);
    uint8_t* _assignee_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, assignee);
    uintptr_t _assignee_len = (_assignee_ptr && _assignee_size > 0) ? (uintptr_t)_assignee_size : 0;
    FfiBuf_u8 _buf = boltffi_create_task_with_assignee(id, (const uint8_t*)_title_c, title ? strlen(_title_c) : 0, (const uint8_t*)_priority_ptr, (uintptr_t)_priority_len, (const uint8_t*)_status_ptr, (uintptr_t)_status_len, (const uint8_t*)_assignee_ptr, (uintptr_t)_assignee_len);
    if (title) (*env)->ReleaseStringUTFChars(env, title, _title_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1task_1with_1subtasks(JNIEnv *env, jclass cls, jlong id, jstring title, jobject priority, jobject subtasks) {
    const char* _title_c = title ? (*env)->GetStringUTFChars(env, title, NULL) : NULL;
    jlong _priority_size = (*env)->GetDirectBufferCapacity(env, priority);
    uint8_t* _priority_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, priority);
    uintptr_t _priority_len = (_priority_ptr && _priority_size > 0) ? (uintptr_t)_priority_size : 0;
    jlong _subtasks_size = (*env)->GetDirectBufferCapacity(env, subtasks);
    uint8_t* _subtasks_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, subtasks);
    uintptr_t _subtasks_len = (_subtasks_ptr && _subtasks_size > 0) ? (uintptr_t)_subtasks_size : 0;
    FfiBuf_u8 _buf = boltffi_create_task_with_subtasks(id, (const uint8_t*)_title_c, title ? strlen(_title_c) : 0, (const uint8_t*)_priority_ptr, (uintptr_t)_priority_len, (const uint8_t*)_subtasks_ptr, (uintptr_t)_subtasks_len);
    if (title) (*env)->ReleaseStringUTFChars(env, title, _title_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1count_1all_1subtasks(JNIEnv *env, jclass cls, jobject task) {
    jlong _task_size = (*env)->GetDirectBufferCapacity(env, task);
    uint8_t* _task_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, task);
    uintptr_t _task_len = (_task_ptr && _task_size > 0) ? (uintptr_t)_task_size : 0;
    int32_t _result = boltffi_count_all_subtasks((const uint8_t*)_task_ptr, (uintptr_t)_task_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1project(JNIEnv *env, jclass cls, jstring name, jobject owner, jobject tasks) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    jlong _owner_size = (*env)->GetDirectBufferCapacity(env, owner);
    uint8_t* _owner_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, owner);
    uintptr_t _owner_len = (_owner_ptr && _owner_size > 0) ? (uintptr_t)_owner_size : 0;
    jlong _tasks_size = (*env)->GetDirectBufferCapacity(env, tasks);
    uint8_t* _tasks_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, tasks);
    uintptr_t _tasks_len = (_tasks_ptr && _tasks_size > 0) ? (uintptr_t)_tasks_size : 0;
    FfiBuf_u8 _buf = boltffi_create_project((const uint8_t*)_name_c, name ? strlen(_name_c) : 0, (const uint8_t*)_owner_ptr, (uintptr_t)_owner_len, (const uint8_t*)_tasks_ptr, (uintptr_t)_tasks_len);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1project_1task_1count(JNIEnv *env, jclass cls, jobject project) {
    jlong _project_size = (*env)->GetDirectBufferCapacity(env, project);
    uint8_t* _project_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, project);
    uintptr_t _project_len = (_project_ptr && _project_size > 0) ? (uintptr_t)_project_size : 0;
    int32_t _result = boltffi_get_project_task_count((const uint8_t*)_project_ptr, (uintptr_t)_project_len);
    return _result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1find_1task_1by_1priority(JNIEnv *env, jclass cls, jobject project, jobject priority) {
    jlong _project_size = (*env)->GetDirectBufferCapacity(env, project);
    uint8_t* _project_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, project);
    uintptr_t _project_len = (_project_ptr && _project_size > 0) ? (uintptr_t)_project_size : 0;
    jlong _priority_size = (*env)->GetDirectBufferCapacity(env, priority);
    uint8_t* _priority_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, priority);
    uintptr_t _priority_len = (_priority_ptr && _priority_size > 0) ? (uintptr_t)_priority_size : 0;
    FfiBuf_u8 _buf = boltffi_find_task_by_priority((const uint8_t*)_project_ptr, (uintptr_t)_project_len, (const uint8_t*)_priority_ptr, (uintptr_t)_priority_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1high_1priority_1tasks(JNIEnv *env, jclass cls, jobject project) {
    jlong _project_size = (*env)->GetDirectBufferCapacity(env, project);
    uint8_t* _project_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, project);
    uintptr_t _project_len = (_project_ptr && _project_size > 0) ? (uintptr_t)_project_size : 0;
    FfiBuf_u8 _buf = boltffi_get_high_priority_tasks((const uint8_t*)_project_ptr, (uintptr_t)_project_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1nested_1coordinates(JNIEnv *env, jclass cls) {
    FfiBuf_u8 _buf = boltffi_create_nested_coordinates();
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1flatten_1coordinates(JNIEnv *env, jclass cls, jobject nested) {
    jlong _nested_size = (*env)->GetDirectBufferCapacity(env, nested);
    uint8_t* _nested_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, nested);
    uintptr_t _nested_len = (_nested_ptr && _nested_size > 0) ? (uintptr_t)_nested_size : 0;
    FfiBuf_u8 _buf = boltffi_flatten_coordinates((const uint8_t*)_nested_ptr, (uintptr_t)_nested_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1optional_1person(JNIEnv *env, jclass cls, jstring name, jint age, jboolean has_address) {
    const char* _name_c = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    FfiBuf_u8 _buf = boltffi_create_optional_person((const uint8_t*)_name_c, name ? strlen(_name_c) : 0, age, has_address);
    if (name) (*env)->ReleaseStringUTFChars(env, name, _name_c);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1optional_1task_1status(JNIEnv *env, jclass cls, jobject task) {
    jlong _task_size = (*env)->GetDirectBufferCapacity(env, task);
    uint8_t* _task_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, task);
    uintptr_t _task_len = (_task_ptr && _task_size > 0) ? (uintptr_t)_task_size : 0;
    FfiBuf_u8 _buf = boltffi_get_optional_task_status((const uint8_t*)_task_ptr, (uintptr_t)_task_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1status_1progress(JNIEnv *env, jclass cls, jobject status) {
    jlong _status_size = (*env)->GetDirectBufferCapacity(env, status);
    uint8_t* _status_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, status);
    uintptr_t _status_len = (_status_ptr && _status_size > 0) ? (uintptr_t)_status_size : 0;
    int32_t _result = boltffi_get_status_progress((const uint8_t*)_status_ptr, (uintptr_t)_status_len);
    return _result;
}
JNIEXPORT jboolean JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1is_1status_1complete(JNIEnv *env, jclass cls, jobject status) {
    jlong _status_size = (*env)->GetDirectBufferCapacity(env, status);
    uint8_t* _status_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, status);
    uintptr_t _status_len = (_status_ptr && _status_size > 0) ? (uintptr_t)_status_size : 0;
    bool _result = boltffi_is_status_complete((const uint8_t*)_status_ptr, (uintptr_t)_status_len);
    return (jboolean)_result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1success_1response(JNIEnv *env, jclass cls, jlong request_id, jobject point) {
    jlong _point_size = (*env)->GetDirectBufferCapacity(env, point);
    uint8_t* _point_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, point);
    uintptr_t _point_len = (_point_ptr && _point_size > 0) ? (uintptr_t)_point_size : 0;
    FfiBuf_u8 _buf = boltffi_create_success_response(request_id, (const uint8_t*)_point_ptr, (uintptr_t)_point_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1create_1error_1response(JNIEnv *env, jclass cls, jlong request_id, jobject error) {
    jlong _error_size = (*env)->GetDirectBufferCapacity(env, error);
    uint8_t* _error_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, error);
    uintptr_t _error_len = (_error_ptr && _error_size > 0) ? (uintptr_t)_error_size : 0;
    FfiBuf_u8 _buf = boltffi_create_error_response(request_id, (const uint8_t*)_error_ptr, (uintptr_t)_error_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}
JNIEXPORT jboolean JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1is_1response_1success(JNIEnv *env, jclass cls, jobject response) {
    jlong _response_size = (*env)->GetDirectBufferCapacity(env, response);
    uint8_t* _response_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, response);
    uintptr_t _response_len = (_response_ptr && _response_size > 0) ? (uintptr_t)_response_size : 0;
    bool _result = boltffi_is_response_success((const uint8_t*)_response_ptr, (uintptr_t)_response_len);
    return (jboolean)_result;
}
JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1get_1response_1value(JNIEnv *env, jclass cls, jobject response) {
    jlong _response_size = (*env)->GetDirectBufferCapacity(env, response);
    uint8_t* _response_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, response);
    uintptr_t _response_len = (_response_ptr && _response_size > 0) ? (uintptr_t)_response_size : 0;
    FfiBuf_u8 _buf = boltffi_get_response_value((const uint8_t*)_response_ptr, (uintptr_t)_response_len);
    if (_buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1free_1native_1buffer(JNIEnv *env, jclass cls, jobject buffer) {
    void* ptr = (*env)->GetDirectBufferAddress(env, buffer);
    jlong len = (*env)->GetDirectBufferCapacity(env, buffer);
    if (ptr != NULL) {
        FfiBuf_u8 buf = { (uint8_t*)ptr, (size_t)len, (size_t)len };
        boltffi_free_buf_u8(buf);
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

JNIEXPORT jint JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1add(JNIEnv *env, jclass cls, jint a, jint b) {
    jint _result = boltffi_add(a, b);
    return _result;
}

JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1multiply(JNIEnv *env, jclass cls, jdouble a, jdouble b) {
    jdouble _result = boltffi_multiply(a, b);
    return _result;
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
    uint64_t _result = boltffi_counter_get((void*)handle);
    return (jlong)_result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_data_store_new();
    return (jlong)_handle;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1with_1sample_1data(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_data_store_with_sample_data();
    return (jlong)_handle;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1with_1capacity(JNIEnv *env, jclass cls, jint capacity) {
    void* _handle = boltffi_data_store_with_capacity(capacity);
    return (jlong)_handle;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1with_1initial_1point(JNIEnv *env, jclass cls, jdouble x, jdouble y, jlong timestamp) {
    void* _handle = boltffi_data_store_with_initial_point(x, y, timestamp);
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_data_store_free((void*)handle);
}
JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1add(JNIEnv *env, jclass cls, jlong handle, jobject point) {
    if (handle == 0) return;
    jlong _point_size = (*env)->GetDirectBufferCapacity(env, point);
    uint8_t* _point_ptr = (uint8_t*)(*env)->GetDirectBufferAddress(env, point);
    uintptr_t _point_len = (_point_ptr && _point_size > 0) ? (uintptr_t)_point_size : 0;
    boltffi_data_store_add((void*)handle, (const uint8_t*)_point_ptr, (uintptr_t)_point_len);
}
JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1len(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    uint64_t _result = boltffi_data_store_len((void*)handle);
    return (jlong)_result;
}
JNIEXPORT jboolean JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1is_1empty(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    bool _result = boltffi_data_store_is_empty((void*)handle);
    return (jboolean)_result;
}
JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1foreach(JNIEnv *env, jclass cls, jlong handle, jobject callback) {
    if (handle == 0) return;
    jobject _callback_ref = (*env)->NewGlobalRef(env, (jobject)callback);
    boltffi_data_store_foreach((void*)handle, trampoline_DataPoint, (void*)_callback_ref);
    (*env)->DeleteGlobalRef(env, _callback_ref);
}
JNIEXPORT jdouble JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1sum(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    double _result = boltffi_data_store_sum((void*)handle);
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

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1sum_1complete(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    FfiStatus _status;
    FfiBuf_u8 _buf = boltffi_data_store_async_sum_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
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

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1store_1async_1len_1complete(JNIEnv *env, jclass cls, jlong handle, jlong future) {
    FfiStatus _status;
    FfiBuf_u8 _buf = boltffi_data_store_async_len_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
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
    int64_t _result = boltffi_accumulator_get((void*)handle);
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
    uint64_t _result = boltffi_sensor_monitor_subscriber_count((void*)handle);
    return (jlong)_result;
}

JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1new(JNIEnv *env, jclass cls) {
    void* _handle = boltffi_data_consumer_new();
    return (jlong)_handle;
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) boltffi_data_consumer_free((void*)handle);
}
JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1set_1provider(JNIEnv *env, jclass cls, jlong handle, jlong provider) {
    if (handle == 0) return;
    boltffi_data_consumer_set_provider((void*)handle, (void*)provider);
}
JNIEXPORT jlong JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1data_1consumer_1compute_1sum(JNIEnv *env, jclass cls, jlong handle) {
    if (handle == 0) return 0;
    uint64_t _result = boltffi_data_consumer_compute_sum((void*)handle);
    return (jlong)_result;
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

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1try_1compute_1async_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    FfiBuf_u8 _buf = boltffi_try_compute_async_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
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

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1fetch_1data_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    FfiBuf_u8 _buf = boltffi_fetch_data_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
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

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1make_1string_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    FfiBuf_u8 _buf = boltffi_async_make_string_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
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
    FfiBuf_u8 _buf = boltffi_async_fetch_point_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
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

JNIEXPORT jobject JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1complete(JNIEnv *env, jclass cls, jlong future) {
    FfiStatus _status;
    FfiBuf_u8 _buf = boltffi_async_get_numbers_complete((RustFutureHandle)future, &_status);
    if (_status.code != 0 || _buf.ptr == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)_buf.ptr, (jlong)_buf.len);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1cancel(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_get_numbers_cancel((RustFutureHandle)future);
}

JNIEXPORT void JNICALL Java_com_example_bench_1boltffi_Native_boltffi_1async_1get_1numbers_1free(JNIEnv *env, jclass cls, jlong future) {
    boltffi_async_get_numbers_free((RustFutureHandle)future);
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