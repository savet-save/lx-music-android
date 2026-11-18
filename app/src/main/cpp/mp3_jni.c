#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include "lame.h"

static jlong native_open(JNIEnv *env, jobject obj, jint rate, jint channels) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2) return 0;
    lame_t encoder = lame_init();
    if (!encoder) return 0;
    lame_set_in_samplerate(encoder, rate);
    if (rate < 32000) lame_set_out_samplerate(encoder, 44100);
    lame_set_num_channels(encoder, channels);
    lame_set_brate(encoder, 320);
    lame_set_quality(encoder, 2);
    lame_set_bWriteVbrTag(encoder, 0);
    if (lame_init_params(encoder) < 0) { lame_close(encoder); return 0; }
    return (jlong)(intptr_t)encoder;
}
static jbyteArray native_encode(JNIEnv *env, jobject obj, jlong handle, jshortArray pcm, jint channels) {
    if (!handle || !pcm || channels < 1 || channels > 2) return NULL;
    lame_t encoder = (lame_t)(intptr_t)handle;
    jsize count = (*env)->GetArrayLength(env, pcm);
    double ratio = (double)lame_get_out_samplerate(encoder) / lame_get_in_samplerate(encoder);
    if (ratio < 1.0) ratio = 1.0;
    int size = (int)(1.25 * count * ratio + 7200);
    unsigned char *buffer = malloc(size);
    if (!buffer) return NULL;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!samples) { free(buffer); return NULL; }
    int length = channels == 2 ? lame_encode_buffer_interleaved(encoder, samples, count / 2, buffer, size)
        : lame_encode_buffer(encoder, samples, samples, count, buffer, size);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    if (length < 0) {
        char message[80]; snprintf(message, sizeof(message), "MP3 encoding failed (%d)", length);
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
    }
    jbyteArray result = length < 0 ? NULL : (*env)->NewByteArray(env, length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte *)buffer);
    free(buffer); return result;
}
static jbyteArray native_flush(JNIEnv *env, jobject obj, jlong handle) {
    if (!handle) return NULL;
    unsigned char buffer[7200];
    int length = lame_encode_flush((lame_t)(intptr_t)handle, buffer, sizeof(buffer));
    jbyteArray result = length < 0 ? NULL : (*env)->NewByteArray(env, length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte *)buffer);
    return result;
}
static void native_close(JNIEnv *env, jobject obj, jlong handle) {
    if (handle) lame_close((lame_t)(intptr_t)handle);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass encoder = (*env)->FindClass(env, "cn/toside/music/nativeapp/data/Mp3Encoder");
    if (!encoder) return JNI_ERR;
    static const JNINativeMethod methods[] = {
        {"open", "(II)J", (void *)native_open},
        {"encode", "(J[SI)[B", (void *)native_encode},
        {"flush", "(J)[B", (void *)native_flush},
        {"close", "(J)V", (void *)native_close}
    };
    jint status = (*env)->RegisterNatives(env, encoder, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, encoder);
    return status == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
