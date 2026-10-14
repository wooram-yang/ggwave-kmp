#include "test-cpp.h"
#include "ggwave/ggwave.h"

#include <jni.h>

#include <vector>

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_moreflow_ggwave_internal_JvmGgwaveCodec_initNative(
        JNIEnv *, jobject, jint inputSampleRate, jint outputSampleRate) {
    ggwave_Parameters parameters = ggwave_getDefaultParameters();
    parameters.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleRateInp = static_cast<float>(inputSampleRate);
    parameters.sampleRateOut = static_cast<float>(outputSampleRate);
    return static_cast<jlong>(ggwave_init(parameters));
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_moreflow_ggwave_internal_JvmGgwaveCodec_releaseNative(
        JNIEnv *, jobject, jlong handle) {
    if (handle >= 0) {
        ggwave_free(static_cast<ggwave_Instance>(handle));
    }
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_github_moreflow_ggwave_internal_JvmGgwaveCodec_decodeNative(
        JNIEnv * env, jobject, jlong handle, jshortArray samples) {
    if (handle < 0 || samples == nullptr) return nullptr;

    const jsize sampleCount = env->GetArrayLength(samples);
    jshort * input = env->GetShortArrayElements(samples, nullptr);
    if (input == nullptr) return nullptr;

    char output[256];
    const int decodedCount = ggwave_decode(
            static_cast<ggwave_Instance>(handle),
            reinterpret_cast<char *>(input),
            static_cast<int>(sizeof(jshort) * sampleCount),
            output);
    env->ReleaseShortArrayElements(samples, input, JNI_ABORT);

    if (decodedCount <= 0) return nullptr;
    jbyteArray payload = env->NewByteArray(decodedCount);
    if (payload == nullptr) return nullptr;
    env->SetByteArrayRegion(payload, 0, decodedCount, reinterpret_cast<jbyte *>(output));
    return payload;
}

extern "C"
JNIEXPORT jshortArray JNICALL
Java_io_github_moreflow_ggwave_internal_JvmGgwaveCodec_encodeNative(
        JNIEnv * env,
        jobject,
        jlong handle,
        jbyteArray payload,
        jint protocolId,
        jint volume) {
    if (handle < 0 || payload == nullptr) return nullptr;

    const jsize payloadSize = env->GetArrayLength(payload);
    jbyte * input = env->GetByteArrayElements(payload, nullptr);
    if (input == nullptr) return nullptr;

    const auto instance = static_cast<ggwave_Instance>(handle);
    const auto protocol = static_cast<ggwave_TxProtocolId>(protocolId);
    const int byteCount = ggwave_encode(
            instance,
            reinterpret_cast<char *>(input),
            payloadSize,
            protocol,
            volume,
            nullptr,
            1);
    if (byteCount <= 0 || byteCount % static_cast<int>(sizeof(jshort)) != 0) {
        env->ReleaseByteArrayElements(payload, input, JNI_ABORT);
        return nullptr;
    }

    std::vector<jshort> waveform(static_cast<size_t>(byteCount / sizeof(jshort)));
    const int sampleCount = ggwave_encode(
            instance,
            reinterpret_cast<char *>(input),
            payloadSize,
            protocol,
            volume,
            reinterpret_cast<char *>(waveform.data()),
            0);
    env->ReleaseByteArrayElements(payload, input, JNI_ABORT);

    if (sampleCount <= 0 || sampleCount > static_cast<int>(waveform.size())) return nullptr;
    jshortArray encoded = env->NewShortArray(sampleCount);
    if (encoded == nullptr) return nullptr;
    env->SetShortArrayRegion(encoded, 0, sampleCount, waveform.data());
    return encoded;
}
