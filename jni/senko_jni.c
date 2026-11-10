#include <jni.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "core/amnezia_bundle.h"
#include "core/amnezia_qr.h"
#include "core/net_protect.h"
#include "engine/engine_main.h"
#include "engine/root_client.h"

/* the engine calls back into the vpn service from many threads of its own:
   the protect hook runs on every dial thread, the tun source on the tunnel
   starter. each native thread is attached to the vm the first time it needs a
   JNIEnv and detached by the key destructor when the thread ends */

static JavaVM *g_vm;
static pthread_key_t g_detach_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;

static jobject g_host; /* global reference, set while the engine runs */
static jmethodID g_protect_id;
static jmethodID g_open_tun_id;
static jmethodID g_tun_error_id;

static void detach_thread(void *value) {
    (void)value;
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

static void make_key(void) {
    (void)pthread_key_create(&g_detach_key, detach_thread);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    pthread_once(&g_key_once, make_key);
    return JNI_VERSION_1_6;
}

/* the env of the calling thread, attaching it when it is a native one */
static JNIEnv *thread_env(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    jint state = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (state == JNI_OK) return env;
    if (state != JNI_EDETACHED) return NULL;
#ifdef __ANDROID__
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
#else
    /* the jdk header takes a void ** where android's takes a JNIEnv ** */
    if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != JNI_OK) return NULL;
#endif
    /* the destructor only runs for a thread that stored a value */
    (void)pthread_setspecific(g_detach_key, env);
    return env;
}

/* an exception from the host is logged by the vm and turned into a failure
   here, because it must not travel back into engine C code */
static int java_failed(JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return 1;
}

static int protect_socket(void *ctx, int fd) {
    (void)ctx;
    JNIEnv *env = thread_env();
    if (!env || !g_host) return -1;
    jboolean protected_ok = (*env)->CallBooleanMethod(env, g_host, g_protect_id, (jint)fd);
    if (java_failed(env)) return -1;
    return protected_ok ? 0 : -1;
}

static void copy_java_string(JNIEnv *env, jstring text, char *out, size_t cap) {
    if (!out || !cap) return;
    out[0] = '\0';
    if (!text) return;
    const char *chars = (*env)->GetStringUTFChars(env, text, NULL);
    if (!chars) return;
    snprintf(out, cap, "%s", chars);
    (*env)->ReleaseStringUTFChars(env, text, chars);
}

static int open_tun(void *ctx, const tun_params_t *params, char *error, size_t error_cap) {
    (void)ctx;
    JNIEnv *env = thread_env();
    if (!env || !g_host) {
        snprintf(error, error_cap, "the vpn service is not attached");
        return -1;
    }
    jstring address4 = (*env)->NewStringUTF(env, params->address4);
    jstring address6 = (*env)->NewStringUTF(env, params->address6);
    jstring dns4 = (*env)->NewStringUTF(env, params->dns4);
    if (!address4 || !address6 || !dns4) {
        java_failed(env);
        snprintf(error, error_cap, "out of memory for the tunnel parameters");
        return -1;
    }
    jint fd = (*env)->CallIntMethod(env, g_host, g_open_tun_id, address4, (jint)params->prefix4,
                                    address6, (jint)params->prefix6, dns4, (jint)params->mtu);
    int failed = java_failed(env);
    (*env)->DeleteLocalRef(env, address4);
    (*env)->DeleteLocalRef(env, address6);
    (*env)->DeleteLocalRef(env, dns4);
    if (failed) {
        snprintf(error, error_cap, "the vpn service threw while opening the tunnel");
        return -1;
    }
    if (fd < 0) {
        jstring reason = (jstring)(*env)->CallObjectMethod(env, g_host, g_tun_error_id);
        if (java_failed(env)) reason = NULL;
        copy_java_string(env, reason, error, error_cap);
        if (reason) (*env)->DeleteLocalRef(env, reason);
        if (error && error_cap && !error[0])
            snprintf(error, error_cap, "the vpn service refused");
        return -1;
    }
    return (int)fd;
}

JNIEXPORT jint JNICALL
Java_com_senko_droid_Engine_nativeRun(JNIEnv *env, jclass clazz, jstring files_dir,
                                      jstring socket_path, jstring core_library, jobject host) {
    (void)clazz;
    char files[256];
    char socket_file[256];
    char core_file[256];
    copy_java_string(env, core_library, core_file, sizeof core_file);
    copy_java_string(env, files_dir, files, sizeof files);
    copy_java_string(env, socket_path, socket_file, sizeof socket_file);

    jclass host_class = (*env)->GetObjectClass(env, host);
    g_protect_id = (*env)->GetMethodID(env, host_class, "protect", "(I)Z");
    g_open_tun_id = (*env)->GetMethodID(env, host_class, "openTun",
                                        "(Ljava/lang/String;ILjava/lang/String;ILjava/lang/String;I)I");
    g_tun_error_id = (*env)->GetMethodID(env, host_class, "tunError", "()Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, host_class);
    if (!g_protect_id || !g_open_tun_id || !g_tun_error_id) {
        (*env)->ExceptionClear(env);
        return ENGINE_ERR_ARG;
    }
    g_host = (*env)->NewGlobalRef(env, host);
    if (!g_host) return ENGINE_ERR_SYSTEM;

    /* installed while no engine thread runs, removed after they are joined */
    net_protect_install(protect_socket, NULL);
    engine_config_t config;
    memset(&config, 0, sizeof config);
    config.files_dir = files;
    config.socket_path = socket_file;
    config.source.open = open_tun;
    config.core_library = core_file;
    char error[160];
    engine_status_t status = engine_run(&config, error, sizeof error);
    net_protect_install(NULL, NULL);

    (*env)->DeleteGlobalRef(env, g_host);
    g_host = NULL;
    if (status != ENGINE_OK) fprintf(stderr, "senko: engine did not run: %s\n", error);
    return (jint)status;
}

JNIEXPORT void JNICALL
Java_com_senko_droid_Engine_nativeStop(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    engine_stop();
}

/* the amnezia client's profiles: a vpn:// link, a .vpn file or its json. the c
   decoder of the engine does the work, the app only asks */

static void throw_io(JNIEnv *env, const char *text) {
    jclass io = (*env)->FindClass(env, "java/io/IOException");
    if (io) (*env)->ThrowNew(env, io, text);
}

JNIEXPORT jboolean JNICALL
Java_com_senko_droid_Engine_nativeAmneziaLooksLike(JNIEnv *env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (!data) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return JNI_FALSE;
    int looks = amz_bundle_looks_like((const char *)bytes, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return looks ? JNI_TRUE : JNI_FALSE;
}

/* null when the input is not a bundle, an IOException with the reason when it is
   one that cannot be used */
JNIEXPORT jstring JNICALL
Java_com_senko_droid_Engine_nativeAmneziaExtract(JNIEnv *env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (!data) return NULL;
    enum { CONF_CAP = 64 * 1024 };
    char *conf = malloc(CONF_CAP);
    if (!conf) {
        throw_io(env, "not enough memory to read the profile");
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        free(conf);
        return NULL;
    }
    char reason[192] = "amnezia bundle could not be read";
    size_t out_len = 0;
    amz_status_t status = amz_bundle_extract_conf((const char *)bytes, (size_t)length, conf,
                                                  CONF_CAP, &out_len, reason, sizeof reason);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    jstring result = NULL;
    if (status == AMZ_OK) {
        conf[out_len < CONF_CAP ? out_len : CONF_CAP - 1] = '\0';
        result = (*env)->NewStringUTF(env, conf);
    } else if (status != AMZ_ERR_NOT_BUNDLE) {
        throw_io(env, reason[0] ? reason : "amnezia bundle could not be read");
    }
    free(conf);
    return result;
}

/* the amnezia client shows a profile as a series of qr frames, collected here */
JNIEXPORT jlong JNICALL
Java_com_senko_droid_Engine_nativeQrNew(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    amz_qr_t *series = calloc(1, sizeof *series);
    if (series) amz_qr_init(series);
    return (jlong)(intptr_t)series;
}

JNIEXPORT void JNICALL
Java_com_senko_droid_Engine_nativeQrFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    amz_qr_t *series = (amz_qr_t *)(intptr_t)handle;
    if (!series) return;
    amz_qr_free(series);
    free(series);
}

/* 0 not a frame, 1 part collected, 2 repeat, 3 done, -1 no room */
JNIEXPORT jint JNICALL
Java_com_senko_droid_Engine_nativeQrAdd(JNIEnv *env, jclass clazz, jlong handle, jstring text) {
    (void)clazz;
    amz_qr_t *series = (amz_qr_t *)(intptr_t)handle;
    if (!series || !text) return AMZ_QR_NOT_FRAME;
    const char *chars = (*env)->GetStringUTFChars(env, text, NULL);
    if (!chars) return AMZ_QR_NOT_FRAME;
    amz_qr_status_t status = amz_qr_add(series, chars, strlen(chars));
    (*env)->ReleaseStringUTFChars(env, text, chars);
    return (jint)status;
}

JNIEXPORT jstring JNICALL
Java_com_senko_droid_Engine_nativeQrResult(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    amz_qr_t *series = (amz_qr_t *)(intptr_t)handle;
    if (!series) return NULL;
    char *out = malloc(AMZ_QR_MAX_BYTES * 2);
    if (!out) return NULL;
    size_t out_len = 0;
    jstring result = NULL;
    if (amz_qr_result(series, out, AMZ_QR_MAX_BYTES * 2, &out_len) == 0) {
        out[out_len] = '\0';
        result = (*env)->NewStringUTF(env, out);
    }
    free(out);
    return result;
}

/* the root helper of android 2.3 to 3.x (rootd/). the host class calls these from the engine's
   own threads, where a failed dial must come back as a sentence and not as a crash */

JNIEXPORT jint JNICALL
Java_com_senko_droid_Engine_nativeRootOpenTun(JNIEnv *env, jclass clazz, jstring socket_path,
                                              jstring address4, jint prefix4, jstring dns4, jint mtu) {
    (void)clazz;
    char socket_file[256];
    tun_params_t params;
    memset(&params, 0, sizeof params);
    copy_java_string(env, socket_path, socket_file, sizeof socket_file);
    copy_java_string(env, address4, params.address4, sizeof params.address4);
    copy_java_string(env, dns4, params.dns4, sizeof params.dns4);
    if (prefix4 < 0 || prefix4 > 32 || mtu < 0 || mtu > 65535) {
        throw_io(env, "the tunnel parameters are out of range");
        return -1;
    }
    params.prefix4 = (uint8_t)prefix4;
    params.mtu = (uint16_t)mtu;
    char error[160];
    error[0] = '\0';
    int fd = root_client_open_tun(socket_file, &params, error, sizeof error);
    if (fd < 0) {
        throw_io(env, error[0] ? error : "the root helper refused the tunnel");
        return -1;
    }
    return (jint)fd;
}

/* null when the socket is out of the tunnel's reach, else the reason */
JNIEXPORT jstring JNICALL
Java_com_senko_droid_Engine_nativeRootProtect(JNIEnv *env, jclass clazz, jstring socket_path, jint fd) {
    (void)clazz;
    char socket_file[256];
    copy_java_string(env, socket_path, socket_file, sizeof socket_file);
    char error[160];
    error[0] = '\0';
    if (root_client_protect(socket_file, (int)fd, error, sizeof error) == 0) return NULL;
    return (*env)->NewStringUTF(env, error[0] ? error : "the root helper refused the socket");
}

JNIEXPORT jstring JNICALL
Java_com_senko_droid_Engine_nativeRootPing(JNIEnv *env, jclass clazz, jstring socket_path) {
    (void)clazz;
    char socket_file[256];
    copy_java_string(env, socket_path, socket_file, sizeof socket_file);
    char error[160];
    error[0] = '\0';
    if (root_client_ping(socket_file, error, sizeof error) == 0) return NULL;
    return (*env)->NewStringUTF(env, error[0] ? error : "the root helper does not answer");
}
