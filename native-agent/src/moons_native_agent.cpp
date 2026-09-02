#ifdef _MSC_VER
#define _CRT_SECURE_NO_WARNINGS
#endif

#include <jni.h>
#include <jvmti.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr char kDefaultBridge[] =
        "com.blanoir.moons.agent.core.NativeTransformerBridge";
constexpr char kTransformSignature[] =
        "(Ljava/lang/String;Ljava/lang/ClassLoader;[BI)[B";

JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_transform_method = nullptr;
std::vector<std::string> g_includes;
std::string g_bridge_name = kDefaultBridge;
std::atomic<bool> g_ready{false};
thread_local bool g_inside_transform = false;

const char* displayName() {
    const char* configured = std::getenv("MOONS_NAME");
    return configured == nullptr || configured[0] == '\0' ? "Moons" : configured;
}

void log(const char* message) {
    std::fprintf(stderr, "[%s native] %s\n", displayName(), message);
    std::fflush(stderr);
}

void logJvmtiError(const char* operation, jvmtiError error) {
    char* name = nullptr;
    if (g_jvmti != nullptr
            && g_jvmti->GetErrorName(error, &name) == JVMTI_ERROR_NONE
            && name != nullptr) {
        std::fprintf(stderr, "[%s native] %s failed: %s (%d)\n",
                displayName(), operation, name, static_cast<int>(error));
        g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    } else {
        std::fprintf(stderr, "[%s native] %s failed: JVMTI error %d\n",
                displayName(), operation, static_cast<int>(error));
    }
    std::fflush(stderr);
}

std::unordered_map<std::string, std::string> parseOptions(const char* raw_options) {
    std::unordered_map<std::string, std::string> result;
    if (raw_options == nullptr) return result;

    std::string options(raw_options);
    std::size_t start = 0;
    while (start <= options.size()) {
        const std::size_t end = options.find(';', start);
        const std::string part = options.substr(
                start, end == std::string::npos ? std::string::npos : end - start);
        const std::size_t separator = part.find('=');
        if (separator != std::string::npos && separator > 0) {
            result.emplace(part.substr(0, separator), part.substr(separator + 1));
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return result;
}

std::vector<std::string> splitPrefixes(const std::string& raw) {
    std::vector<std::string> result;
    std::size_t start = 0;
    while (start <= raw.size()) {
        const std::size_t end = raw.find(',', start);
        std::string prefix = raw.substr(
                start, end == std::string::npos ? std::string::npos : end - start);
        if (!prefix.empty()) result.push_back(std::move(prefix));
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return result;
}

bool matchesInclude(const char* class_name) {
    if (class_name == nullptr) return false;
    const std::string_view name(class_name);
    return std::any_of(g_includes.begin(), g_includes.end(), [&](const std::string& prefix) {
        return name.size() >= prefix.size()
                && name.compare(0, prefix.size(), prefix) == 0;
    });
}

void describeAndClear(JNIEnv* env, const char* operation) {
    if (!env->ExceptionCheck()) return;
    std::fprintf(stderr, "[%s native] Java exception during %s\n", displayName(), operation);
    env->ExceptionDescribe();
    env->ExceptionClear();
    std::fflush(stderr);
}

bool initializeBridge(JNIEnv* env) {
    jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
    if (class_loader_class == nullptr) {
        describeAndClear(env, "ClassLoader lookup");
        return false;
    }
    jmethodID get_system_loader = env->GetStaticMethodID(
            class_loader_class,
            "getSystemClassLoader",
            "()Ljava/lang/ClassLoader;");
    if (get_system_loader == nullptr) {
        describeAndClear(env, "ClassLoader.getSystemClassLoader lookup");
        env->DeleteLocalRef(class_loader_class);
        return false;
    }
    jobject system_loader = env->CallStaticObjectMethod(class_loader_class, get_system_loader);
    if (system_loader == nullptr || env->ExceptionCheck()) {
        describeAndClear(env, "ClassLoader.getSystemClassLoader");
        env->DeleteLocalRef(class_loader_class);
        return false;
    }

    jclass class_class = env->FindClass("java/lang/Class");
    jmethodID for_name = class_class == nullptr ? nullptr : env->GetStaticMethodID(
            class_class,
            "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (class_class == nullptr || for_name == nullptr) {
        describeAndClear(env, "Class.forName lookup");
        env->DeleteLocalRef(system_loader);
        env->DeleteLocalRef(class_loader_class);
        if (class_class != nullptr) env->DeleteLocalRef(class_class);
        return false;
    }

    jstring bridge_name = env->NewStringUTF(g_bridge_name.c_str());
    jobject bridge = bridge_name == nullptr ? nullptr : env->CallStaticObjectMethod(
            class_class, for_name, bridge_name, JNI_TRUE, system_loader);
    if (bridge == nullptr || env->ExceptionCheck()) {
        describeAndClear(env, "Java transformer bridge initialization");
        if (bridge_name != nullptr) env->DeleteLocalRef(bridge_name);
        env->DeleteLocalRef(class_class);
        env->DeleteLocalRef(system_loader);
        env->DeleteLocalRef(class_loader_class);
        return false;
    }

    auto bridge_class = reinterpret_cast<jclass>(bridge);
    jmethodID transform = env->GetStaticMethodID(
            bridge_class, "transform", kTransformSignature);
    if (transform == nullptr || env->ExceptionCheck()) {
        describeAndClear(env, "Java transformer method lookup");
        env->DeleteLocalRef(bridge);
        env->DeleteLocalRef(bridge_name);
        env->DeleteLocalRef(class_class);
        env->DeleteLocalRef(system_loader);
        env->DeleteLocalRef(class_loader_class);
        return false;
    }

    g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(bridge_class));
    g_transform_method = transform;

    env->DeleteLocalRef(bridge);
    env->DeleteLocalRef(bridge_name);
    env->DeleteLocalRef(class_class);
    env->DeleteLocalRef(system_loader);
    env->DeleteLocalRef(class_loader_class);
    return g_bridge_class != nullptr;
}

void JNICALL onClassFileLoad(
        jvmtiEnv* jvmti,
        JNIEnv* env,
        jclass class_being_redefined,
        jobject loader,
        const char* name,
        jobject,
        jint class_data_length,
        const unsigned char* class_data,
        jint* new_class_data_length,
        unsigned char** new_class_data) {
    if (!g_ready.load(std::memory_order_acquire)
            || g_inside_transform
            || !matchesInclude(name)
            || class_data == nullptr
            || class_data_length <= 0) {
        return;
    }

    g_inside_transform = true;
    jstring class_name = env->NewStringUTF(name);
    jbyteArray input = env->NewByteArray(class_data_length);
    if (class_name == nullptr || input == nullptr || env->ExceptionCheck()) {
        describeAndClear(env, "transform input allocation");
        if (class_name != nullptr) env->DeleteLocalRef(class_name);
        if (input != nullptr) env->DeleteLocalRef(input);
        g_inside_transform = false;
        return;
    }

    env->SetByteArrayRegion(input, 0, class_data_length,
            reinterpret_cast<const jbyte*>(class_data));
    if (env->ExceptionCheck()) {
        describeAndClear(env, "transform input copy");
        env->DeleteLocalRef(input);
        env->DeleteLocalRef(class_name);
        g_inside_transform = false;
        return;
    }

    const jint flags = class_being_redefined == nullptr ? 0 : 1;
    auto output = reinterpret_cast<jbyteArray>(env->CallStaticObjectMethod(
            g_bridge_class,
            g_transform_method,
            class_name,
            loader,
            input,
            flags));
    if (env->ExceptionCheck()) {
        describeAndClear(env, name);
    } else if (output != nullptr) {
        const jsize output_length = env->GetArrayLength(output);
        if (output_length > 0) {
            unsigned char* allocated = nullptr;
            const jvmtiError allocation_error = jvmti->Allocate(output_length, &allocated);
            if (allocation_error == JVMTI_ERROR_NONE && allocated != nullptr) {
                env->GetByteArrayRegion(output, 0, output_length,
                        reinterpret_cast<jbyte*>(allocated));
                if (env->ExceptionCheck()) {
                    describeAndClear(env, "transform output copy");
                    jvmti->Deallocate(allocated);
                } else {
                    *new_class_data_length = output_length;
                    *new_class_data = allocated;
                }
            } else {
                logJvmtiError("Allocate transformed class bytes", allocation_error);
            }
        }
        env->DeleteLocalRef(output);
    }

    env->DeleteLocalRef(input);
    env->DeleteLocalRef(class_name);
    g_inside_transform = false;
}

void JNICALL onVmInit(jvmtiEnv* jvmti, JNIEnv* env, jthread) {
    if (!initializeBridge(env)) {
        log("Java transformer bridge initialization failed; hook remains disabled");
        return;
    }

    const jvmtiError error = jvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
    if (error != JVMTI_ERROR_NONE) {
        logJvmtiError("Enable ClassFileLoadHook", error);
        return;
    }
    g_ready.store(true, std::memory_order_release);
    std::fprintf(stderr, "[%s native] active: bridge=%s prefixes=%zu\n",
            displayName(), g_bridge_name.c_str(), g_includes.size());
    std::fflush(stderr);
}

bool addCapabilities() {
    jvmtiCapabilities potential{};
    jvmtiError error = g_jvmti->GetPotentialCapabilities(&potential);
    if (error != JVMTI_ERROR_NONE) {
        logJvmtiError("GetPotentialCapabilities", error);
        return false;
    }

    jvmtiCapabilities requested{};
    requested.can_retransform_classes = potential.can_retransform_classes;
    requested.can_retransform_any_class = potential.can_retransform_any_class;
    error = g_jvmti->AddCapabilities(&requested);
    if (error != JVMTI_ERROR_NONE) {
        logJvmtiError("AddCapabilities", error);
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(
        JavaVM* vm, char* options, void*) {
    g_vm = vm;
    const jint env_result = vm->GetEnv(
            reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2);
    if (env_result != JNI_OK || g_jvmti == nullptr) {
        log("GetEnv(JVMTI_VERSION_1_2) failed");
        return JNI_ERR;
    }

    const auto parsed = parseOptions(options);
    const auto jar = parsed.find("jar");
    if (jar == parsed.end() || jar->second.empty()) {
        log("missing required option: jar=<absolute transformer jar path>");
        return JNI_ERR;
    }

    const auto bridge = parsed.find("bridge");
    if (bridge != parsed.end() && !bridge->second.empty()) {
        g_bridge_name = bridge->second;
    }
    const auto includes = parsed.find("include");
    g_includes = splitPrefixes(includes == parsed.end()
            ? "net/minecraft/,com/mojang/,net/caffeinemc/"
            : includes->second);
    if (g_includes.empty()) {
        log("include list must contain at least one class prefix");
        return JNI_ERR;
    }

    const auto bootstrap = parsed.find("bootstrap");
    if (bootstrap != parsed.end() && !bootstrap->second.empty()) {
        const jvmtiError bootstrap_error =
                g_jvmti->AddToBootstrapClassLoaderSearch(bootstrap->second.c_str());
        if (bootstrap_error != JVMTI_ERROR_NONE) {
            logJvmtiError("AddToBootstrapClassLoaderSearch", bootstrap_error);
            return JNI_ERR;
        }
    }
    const jvmtiError search_error =
            g_jvmti->AddToSystemClassLoaderSearch(jar->second.c_str());
    if (search_error != JVMTI_ERROR_NONE) {
        logJvmtiError("AddToSystemClassLoaderSearch", search_error);
        return JNI_ERR;
    }

    if (!addCapabilities()) return JNI_ERR;

    jvmtiEventCallbacks callbacks{};
    callbacks.VMInit = &onVmInit;
    callbacks.ClassFileLoadHook = &onClassFileLoad;
    jvmtiError error = g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (error != JVMTI_ERROR_NONE) {
        logJvmtiError("SetEventCallbacks", error);
        return JNI_ERR;
    }
    error = g_jvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr);
    if (error != JVMTI_ERROR_NONE) {
        logJvmtiError("Enable VMInit", error);
        return JNI_ERR;
    }

    log("loaded; waiting for VMInit");
    return JNI_OK;
}

extern "C" JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm) {
    g_ready.store(false, std::memory_order_release);
    if (g_jvmti != nullptr) {
        g_jvmti->SetEventNotificationMode(
                JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
        g_jvmti->SetEventNotificationMode(
                JVMTI_DISABLE, JVMTI_EVENT_VM_INIT, nullptr);
    }
    JNIEnv* env = nullptr;
    if (g_bridge_class != nullptr
            && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK
            && env != nullptr) {
        env->DeleteGlobalRef(g_bridge_class);
    }
    g_bridge_class = nullptr;
    g_transform_method = nullptr;
    g_jvmti = nullptr;
    g_vm = nullptr;
}
