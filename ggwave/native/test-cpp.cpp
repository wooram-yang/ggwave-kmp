//
// Created by ggerganov on 26.04.21.
// Modified by Wooram Yang on 24.03.24
//

#include "test-cpp.h"
#include "ggwave/ggwave.h"

#include <jni.h>

#include <vector>

namespace {
    ggwave_Instance g_ggwave = -1;

    void freeInstance() {
        if (g_ggwave >= 0) {
            ggwave_free(g_ggwave);
            g_ggwave = -1;
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_wooramyang_ggwave_internal_JVMGGWave_initNative(JNIEnv * env, jobject obj, jint sampleRate) {
    freeInstance();

    ggwave_Parameters parameters = ggwave_getDefaultParameters();
    parameters.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleRateInp = (float) sampleRate;
    parameters.sampleRateOut = (float) sampleRate;
    g_ggwave = ggwave_init(parameters);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_wooramyang_ggwave_internal_JVMGGWave_releaseNative(JNIEnv * env, jobject obj) {
    freeInstance();
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_github_wooramyang_ggwave_internal_JVMGGWave_decodeNative(JNIEnv *env, jobject thiz, jshortArray data) {
    if (g_ggwave < 0 || data == nullptr) {
        return nullptr;
    }

    jsize dataSize = env->GetArrayLength(data);
    jshort * cData = env->GetShortArrayElements(data, nullptr);
    if (cData == nullptr) {
        return nullptr;
    }

    char output[256];
    int ret = ggwave_decode(g_ggwave, (char *) cData, 2 * dataSize, output);
    env->ReleaseShortArrayElements(data, cData, JNI_ABORT);

    if (ret <= 0) {
        return nullptr;
    }

    jbyteArray message = env->NewByteArray(ret);
    if (message == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(message, 0, ret, (jbyte*) output);
    return message;
}

extern "C"
JNIEXPORT jshortArray JNICALL
Java_io_github_wooramyang_ggwave_internal_JVMGGWave_encodeNative(JNIEnv *env, jobject thiz, jstring message, jint volume) {
    if (g_ggwave < 0 || message == nullptr) {
        return nullptr;
    }

    const char * utf = env->GetStringUTFChars(message, nullptr);
    if (utf == nullptr) {
        return nullptr;
    }
    const int nbytes = (int) env->GetStringUTFLength(message);

    const int n = ggwave_encode(
        g_ggwave,
        utf,
        nbytes,
        GGWAVE_TX_PROTOCOL_AUDIBLE_FAST,
        volume,
        nullptr,
        1);

    if (n <= 0) {
        env->ReleaseStringUTFChars(message, utf);
        return nullptr;
    }

    std::vector<char> waveform((size_t) n);
    const int samples = ggwave_encode(
        g_ggwave,
        utf,
        nbytes,
        GGWAVE_TX_PROTOCOL_AUDIBLE_FAST,
        volume,
        waveform.data(),
        0);
    env->ReleaseStringUTFChars(message, utf);

    if (samples <= 0) {
        return nullptr;
    }

    jshortArray encoded = env->NewShortArray(samples);
    if (encoded == nullptr) {
        return nullptr;
    }
    env->SetShortArrayRegion(encoded, 0, samples, (jshort*) waveform.data());
    return encoded;
}
