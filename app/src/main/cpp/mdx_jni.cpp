#define ORT_API_MANUAL_INIT
#include <onnxruntime_cxx_api.h>
#include <dlfcn.h>
#include <jni.h>
#include <malloc.h>

#include "mdx_audio.h"

#include <algorithm>
#include <array>
#include <stdexcept>
#include <string>

namespace {
void initialize_runtime() {
    // The C++ wrappers use the C API table. Keep the runtime loaded for its lifetime.
    static const auto* api = [] {
        void* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        auto get_api_base = reinterpret_cast<decltype(&OrtGetApiBase)>(dlsym(library, "OrtGetApiBase"));
        const auto* result = get_api_base ? get_api_base()->GetApi(ORT_API_VERSION) : nullptr;
        if (!result) {
            dlclose(library);
            throw std::runtime_error("Incompatible ONNX Runtime");
        }
        Ort::InitApi(result);
        return result;
    }();
    (void) api;
}

class JavaString {
    JNIEnv* env;
    jstring input;

public:
    const char* value;
    JavaString(JNIEnv* env, jstring input) : env(env), input(input), value(env->GetStringUTFChars(input, nullptr)) {}
    ~JavaString() { if (value) env->ReleaseStringUTFChars(input, value); }
};

struct ReleaseUnusedPages {
    // Return freed inference buffers to Android before the user starts recording.
    ~ReleaseUnusedPages() { mallopt(M_PURGE, 0); }
};

constexpr std::array<int64_t, 4> tensor_shape{1, 4, mdx::frequency_bins, mdx::time_bins};

void validate_tensor(const Ort::TypeInfo& type) {
    const auto tensor = type.GetTensorTypeAndShapeInfo();
    const auto shape = tensor.GetShape();
    if (tensor.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        shape.size() != tensor_shape.size() || (shape[0] != -1 && shape[0] != 1) ||
        shape[1] != tensor_shape[1] || shape[2] != tensor_shape[2] || shape[3] != tensor_shape[3]) {
        throw std::runtime_error("Unsupported MDX-Net tensor shape");
    }
}

Ort::Session load_model(Ort::Env& runtime, const char* model_path, int threads) {
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(threads).SetExecutionMode(ORT_SEQUENTIAL)
           .SetGraphOptimizationLevel(ORT_ENABLE_EXTENDED).DisableCpuMemArena().DisableMemPattern();
    options.AddConfigEntry("session.intra_op.allow_spinning", "0");

    // The CPU provider halved the workspace and reclaimed it after each run on ARM64.
    if constexpr (sizeof(void*) == 4) {
        const auto providers = Ort::GetAvailableProviders();
        if (std::find(providers.begin(), providers.end(), "XnnpackExecutionProvider") != providers.end()) {
            try {
                auto accelerated = options.Clone();
                accelerated.SetIntraOpNumThreads(1).AppendExecutionProvider(
                    "XNNPACK", {{"intra_op_num_threads", std::to_string(threads)}});
                return Ort::Session(runtime, model_path, accelerated);
            } catch (const Ort::Exception&) {
                // Some Android CPUs cannot initialize XNNPACK. Use the CPU provider in that case.
            }
        }
    }
    return Ort::Session(runtime, model_path, options);
}

jboolean separate_audio(
    JNIEnv* env, jobject, jstring model_path, jstring input_path, jstring output_path, jlong frames,
    jint strength_percent, jint overlap_ms, jint threads, jobject callback) {
    JavaString model(env, model_path);
    if (!model.value) return JNI_FALSE;
    JavaString input(env, input_path);
    if (!input.value) return JNI_FALSE;
    JavaString output(env, output_path);
    if (!output.value) return JNI_FALSE;
    const auto callback_class = env->GetObjectClass(callback);
    if (!callback_class) return JNI_FALSE;
    const auto report = env->GetMethodID(callback_class, "test", "(I)Z");
    env->DeleteLocalRef(callback_class);
    if (!report) return JNI_FALSE;
    auto progress = [&](int percent) {
        const auto keep_running = env->CallBooleanMethod(callback, report, jint(percent));
        return !env->ExceptionCheck() && keep_running == JNI_TRUE;
    };

    try {
        if (strength_percent < 90 || strength_percent > 120 || overlap_ms < 125 || overlap_ms > 1000 ||
            threads < 1 || threads > 4) {
            throw std::invalid_argument("Unsupported MDX-Net parameters");
        }
        if (!progress(0)) return JNI_FALSE;
        initialize_runtime();
        const ReleaseUnusedPages release_unused_pages;
        Ort::Env runtime(ORT_LOGGING_LEVEL_ERROR, "lx-mdx");
        auto session = load_model(runtime, model.value, threads);
        if (session.GetInputCount() != 1 || session.GetOutputCount() != 1) {
            throw std::runtime_error("Unexpected MDX-Net inputs or outputs");
        }
        validate_tensor(session.GetInputTypeInfo(0));
        validate_tensor(session.GetOutputTypeInfo(0));
        Ort::AllocatorWithDefaultOptions allocator;
        const auto input_name = session.GetInputNameAllocated(0, allocator);
        const auto output_name = session.GetOutputNameAllocated(0, allocator);
        const char* input_names[] = {input_name.get()};
        const char* output_names[] = {output_name.get()};
        auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        auto predict = [&](float* spec, float* result) {
            auto input_tensor = Ort::Value::CreateTensor<float>(memory, spec, mdx::tensor_size,
                tensor_shape.data(), tensor_shape.size());
            auto output_tensor = Ort::Value::CreateTensor<float>(memory, result, mdx::tensor_size,
                tensor_shape.data(), tensor_shape.size());
            session.Run(Ort::RunOptions{nullptr}, input_names, &input_tensor, 1, output_names, &output_tensor, 1);
        };
        return mdx::separate(input.value, output.value, frames, strength_percent, overlap_ms, predict, progress)
            ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        const auto exception = env->FindClass("java/io/IOException");
        if (exception) env->ThrowNew(exception, error.what());
        return JNI_FALSE;
    }
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    const auto bridge = env->FindClass("cn/toside/music/nativeapp/karaoke/NativeMdx");
    if (!bridge) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"separate", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JIIILjava/util/function/IntPredicate;)Z",
            reinterpret_cast<void*>(separate_audio)},
    };
    const auto result = env->RegisterNatives(bridge, methods, 1);
    env->DeleteLocalRef(bridge);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
