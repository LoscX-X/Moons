#include <jni.h>
#include <jvmti.h>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <shlobj.h>

#include <atomic>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

constexpr char kBridgeClass[] =
        "com.blanoir.moons.agent.core.NativeTransformerBridge";
constexpr char kMinecraftClass[] = "net/minecraft/client/Minecraft";
constexpr char kTransformSignature[] =
        "(Ljava/lang/String;Ljava/lang/ClassLoader;[BI)[B";
constexpr char kStartSignature[] =
         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;"
         "Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)Z";

struct BridgeConfig {
    std::string payload;
    std::string bootstrap;
    std::string home;
    std::string name;
    std::string hwid;
    std::string attempt;
};

JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
jobject g_payload_loader = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_transform_method = nullptr;
jmethodID g_start_method = nullptr;
jmethodID g_game_bridge_names_method = nullptr;
jmethodID g_game_bridge_bytes_method = nullptr;
jclass g_game_runtime_bridge_class = nullptr;
jclass g_game_agent_bridge_class = nullptr;
std::unordered_set<std::string> g_target_names;
std::atomic<bool> g_ready{false};
std::atomic<bool> g_vm_dead{false};
std::atomic<bool> g_runtime_started{false};
thread_local bool g_inside_transform = false;
std::string g_attempt;
std::mutex g_log_mutex;
std::mutex g_loader_mutex;
jobject g_game_loader = nullptr;
HANDLE g_game_loader_event = nullptr;
HANDLE g_reload_event = nullptr;

std::string wide_to_utf8(const std::wstring& value) {
    if (value.empty()) return {};
    const int size = WideCharToMultiByte(
            CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
            nullptr, 0, nullptr, nullptr);
    if (size <= 0) return "?";
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(
            CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
            result.data(), size, nullptr, nullptr);
    return result;
}

std::filesystem::path data_directory() {
    PWSTR roaming_path = nullptr;
    const HRESULT roaming_result = SHGetKnownFolderPath(
            FOLDERID_RoamingAppData, KF_FLAG_DEFAULT, nullptr, &roaming_path);
    std::filesystem::path base;
    if (SUCCEEDED(roaming_result) && roaming_path != nullptr) {
        base = roaming_path;
    }
    if (roaming_path != nullptr) {
        CoTaskMemFree(roaming_path);
    }
    if (base.empty()) {
        wchar_t buffer[32768] = {};
        const DWORD length = GetTempPathW(32768, buffer);
        base = length > 0 && length < 32768
                ? std::filesystem::path(buffer)
                : std::filesystem::path(L"C:\\Windows\\Temp");
    }
    std::error_code ignored;
    std::filesystem::create_directories(base / L".moons", ignored);
    return base / L".moons";
}

std::wstring reload_event_name() {
    return L"Local\\MoonsBridgeReload-" + std::to_wstring(GetCurrentProcessId());
}

void log_line(const std::string& line) {
    std::lock_guard<std::mutex> guard(g_log_mutex);
    std::ofstream output(data_directory() / L"bridge-dll.log", std::ios::app);
    if (!output) return;
    SYSTEMTIME now{};
    GetLocalTime(&now);
    char stamp[64] = {};
    std::snprintf(stamp, sizeof(stamp),
            "%04u-%02u-%02u %02u:%02u:%02u.%03u ",
            now.wYear, now.wMonth, now.wDay,
            now.wHour, now.wMinute, now.wSecond, now.wMilliseconds);
    output << stamp;
    if (!g_attempt.empty()) output << '[' << g_attempt << "] ";
    output << line << '\n';
}

void set_attempt(std::string attempt) {
    std::lock_guard<std::mutex> guard(g_log_mutex);
    g_attempt = std::move(attempt);
}

void log_jvmti_error(const char* operation, jvmtiError error) {
    char* name = nullptr;
    if (g_jvmti != nullptr
            && g_jvmti->GetErrorName(error, &name) == JVMTI_ERROR_NONE
            && name != nullptr) {
        log_line(std::string(operation) + " failed: " + name
                + " (" + std::to_string(static_cast<int>(error)) + ")");
        g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    } else {
        log_line(std::string(operation) + " failed: JVMTI error "
                + std::to_string(static_cast<int>(error)));
    }
}

void describe_and_clear(JNIEnv* env, const char* operation) {
    if (!env->ExceptionCheck()) return;
    env->ExceptionDescribe();
    env->ExceptionClear();
    log_line(std::string("Java exception during ") + operation);
}

jstring new_utf8_string(JNIEnv* env, const std::string& value) {
    if (value.empty()) return env->NewStringUTF("");
    const int wide_size = MultiByteToWideChar(
            CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
            static_cast<int>(value.size()), nullptr, 0);
    if (wide_size <= 0) return env->NewStringUTF(value.c_str());
    std::wstring wide(static_cast<std::size_t>(wide_size), L'\0');
    MultiByteToWideChar(
            CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
            static_cast<int>(value.size()), wide.data(), wide_size);
    return env->NewString(
            reinterpret_cast<const jchar*>(wide.data()),
            static_cast<jsize>(wide.size()));
}

BridgeConfig read_config() {
    BridgeConfig config;
    const DWORD pid = GetCurrentProcessId();
    std::filesystem::path path = data_directory()
            / (L"bridge-" + std::to_wstring(pid) + L".conf");
    if (!std::filesystem::is_regular_file(path)) {
        path = data_directory() / L"bridge.conf";
    }
    std::ifstream input(path);
    std::string line;
    while (std::getline(input, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const std::size_t separator = line.find('=');
        if (separator == std::string::npos) continue;
        const std::string key = line.substr(0, separator);
        const std::string value = line.substr(separator + 1);
        if (key == "payload") config.payload = value;
        else if (key == "bootstrap") config.bootstrap = value;
        else if (key == "home") config.home = value;
        else if (key == "name") config.name = value;
        else if (key == "hwid") config.hwid = value;
        else if (key == "attempt") config.attempt = value;
    }
    input.close();
    if (!config.payload.empty()) {
        std::error_code ignored;
        std::filesystem::remove(path, ignored);
    }
    return config;
}

bool capture_game_loader(JNIEnv* env, jobject loader) {
    if (loader == nullptr) return false;
    std::lock_guard<std::mutex> guard(g_loader_mutex);
    if (g_game_loader == nullptr) {
        g_game_loader = env->NewGlobalRef(loader);
        if (g_game_loader != nullptr && g_game_loader_event != nullptr) {
            SetEvent(g_game_loader_event);
        }
    }
    return g_game_loader != nullptr;
}

jobject local_game_loader(JNIEnv* env) {
    std::lock_guard<std::mutex> guard(g_loader_mutex);
    return g_game_loader == nullptr ? nullptr : env->NewLocalRef(g_game_loader);
}

bool load_java_bridge(JNIEnv* env, const std::string& payload) {
    if (env->PushLocalFrame(64) != JNI_OK) return false;
    bool success = false;

    jclass file_class = env->FindClass("java/io/File");
    jmethodID file_init = file_class == nullptr ? nullptr : env->GetMethodID(
            file_class, "<init>", "(Ljava/lang/String;)V");
    jmethodID to_uri = file_class == nullptr ? nullptr : env->GetMethodID(
            file_class, "toURI", "()Ljava/net/URI;");
    jclass uri_class = env->FindClass("java/net/URI");
    jmethodID to_url = uri_class == nullptr ? nullptr : env->GetMethodID(
            uri_class, "toURL", "()Ljava/net/URL;");
    jclass url_class = env->FindClass("java/net/URL");
    jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class_method = class_loader_class == nullptr ? nullptr
            : env->GetMethodID(class_loader_class, "loadClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
    jclass url_loader_class = env->FindClass("java/net/URLClassLoader");
    jmethodID new_loader_method = url_loader_class == nullptr ? nullptr
            : env->GetStaticMethodID(url_loader_class, "newInstance",
                    "([Ljava/net/URL;Ljava/lang/ClassLoader;)Ljava/net/URLClassLoader;");

    if (env->ExceptionCheck() || file_init == nullptr || to_uri == nullptr
            || to_url == nullptr || url_class == nullptr
            || load_class_method == nullptr
            || new_loader_method == nullptr) {
        describe_and_clear(env, "payload ClassLoader API lookup");
        env->PopLocalFrame(nullptr);
        return false;
    }

    jstring payload_string = new_utf8_string(env, payload);
    jobject file = env->NewObject(file_class, file_init, payload_string);
    jobject uri = file == nullptr ? nullptr : env->CallObjectMethod(file, to_uri);
    jobject url = uri == nullptr ? nullptr : env->CallObjectMethod(uri, to_url);
    jobjectArray urls = url == nullptr ? nullptr : env->NewObjectArray(1, url_class, nullptr);
    if (urls != nullptr) env->SetObjectArrayElement(urls, 0, url);

    // Keep the transformer payload anchored to bootstrap. In a vanilla launch
    // the Minecraft loader is the system AppClassLoader; defining the required
    // game-side facade there must not make NativeTransformerBridge resolve that
    // second RuntimeBridge identity through its parent. A null parent still sees
    // JDK/bootstrap classes and the bootstrap API added above, while all agent,
    // ASM and transformer classes are present in the payload URL itself.
    jobject payload_loader = urls == nullptr ? nullptr : env->CallStaticObjectMethod(
            url_loader_class, new_loader_method, urls, nullptr);

    jstring bridge_name = env->NewStringUTF(kBridgeClass);
    jobject bridge = payload_loader == nullptr ? nullptr : env->CallObjectMethod(
            payload_loader, load_class_method, bridge_name);
    if (env->ExceptionCheck() || bridge == nullptr) {
        describe_and_clear(env, "NativeTransformerBridge load");
        env->PopLocalFrame(nullptr);
        return false;
    }

    auto bridge_class = reinterpret_cast<jclass>(bridge);
    jmethodID transform = env->GetStaticMethodID(
            bridge_class, "transform", kTransformSignature);
    jmethodID targets = env->GetStaticMethodID(
            bridge_class, "targetClassNames", "()[Ljava/lang/String;");
    jmethodID start = env->GetStaticMethodID(
            bridge_class, "startRuntime", kStartSignature);
    jmethodID bridge_names = env->GetStaticMethodID(
            bridge_class, "gameBridgeClassNames", "()[Ljava/lang/String;");
    jmethodID bridge_bytes = env->GetStaticMethodID(
            bridge_class, "gameBridgeClassBytes", "()[[B");
    if (env->ExceptionCheck() || transform == nullptr || targets == nullptr
            || start == nullptr || bridge_names == nullptr || bridge_bytes == nullptr) {
        describe_and_clear(env, "NativeTransformerBridge method lookup");
        env->PopLocalFrame(nullptr);
        return false;
    }

    auto target_array = reinterpret_cast<jobjectArray>(
            env->CallStaticObjectMethod(bridge_class, targets));
    if (env->ExceptionCheck() || target_array == nullptr) {
        describe_and_clear(env, "targetClassNames");
        env->PopLocalFrame(nullptr);
        return false;
    }
    const jsize target_count = env->GetArrayLength(target_array);
    for (jsize index = 0; index < target_count; ++index) {
        auto target = reinterpret_cast<jstring>(
                env->GetObjectArrayElement(target_array, index));
        if (target == nullptr) continue;
        const char* text = env->GetStringUTFChars(target, nullptr);
        if (text != nullptr) {
            g_target_names.emplace(text);
            env->ReleaseStringUTFChars(target, text);
        }
        env->DeleteLocalRef(target);
    }

    g_payload_loader = env->NewGlobalRef(payload_loader);
    g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(bridge_class));
    g_transform_method = transform;
    g_start_method = start;
    g_game_bridge_names_method = bridge_names;
    g_game_bridge_bytes_method = bridge_bytes;
    success = g_payload_loader != nullptr && g_bridge_class != nullptr
            && !g_target_names.empty();
    env->PopLocalFrame(nullptr);
    return success;
}

bool define_game_bridge(JNIEnv* env, jobject game_loader) {
    if (g_game_runtime_bridge_class != nullptr && g_game_agent_bridge_class != nullptr) {
        return true;
    }
    auto names = reinterpret_cast<jobjectArray>(env->CallStaticObjectMethod(
            g_bridge_class, g_game_bridge_names_method));
    auto bytes = reinterpret_cast<jobjectArray>(env->CallStaticObjectMethod(
            g_bridge_class, g_game_bridge_bytes_method));
    if (env->ExceptionCheck() || names == nullptr || bytes == nullptr) {
        describe_and_clear(env, "game bridge byte loading");
        return false;
    }
    const jsize count = env->GetArrayLength(names);
    if (count != env->GetArrayLength(bytes) || count < 3) {
        log_line("game bridge class metadata is inconsistent");
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(names);
        return false;
    }

    bool success = true;
    for (jsize index = 0; index < count; ++index) {
        auto name = reinterpret_cast<jstring>(env->GetObjectArrayElement(names, index));
        auto data = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(bytes, index));
        if (name == nullptr || data == nullptr) {
            success = false;
        } else {
            const char* dotted = env->GetStringUTFChars(name, nullptr);
            const jsize length = env->GetArrayLength(data);
            std::vector<jbyte> class_bytes(static_cast<std::size_t>(length));
            env->GetByteArrayRegion(data, 0, length, class_bytes.data());
            if (dotted == nullptr || env->ExceptionCheck()) {
                describe_and_clear(env, "game bridge byte copy");
                if (dotted != nullptr) env->ReleaseStringUTFChars(name, dotted);
                success = false;
            } else {
                std::string internal(dotted);
                for (char& character : internal) {
                    if (character == '.') character = '/';
                }
                jclass defined = env->DefineClass(
                        internal.c_str(), game_loader,
                        class_bytes.data(), length);
                if (defined == nullptr || env->ExceptionCheck()) {
                    describe_and_clear(env, internal.c_str());
                    success = false;
                } else if (internal == "com/blanoir/moons/api/bridge/RuntimeBridge") {
                    g_game_runtime_bridge_class = reinterpret_cast<jclass>(
                            env->NewGlobalRef(defined));
                } else if (internal == "com/blanoir/moons/api/bridge/AgentBridge") {
                    g_game_agent_bridge_class = reinterpret_cast<jclass>(
                            env->NewGlobalRef(defined));
                }
                env->DeleteLocalRef(defined);
                env->ReleaseStringUTFChars(name, dotted);
            }
        }
        if (data != nullptr) env->DeleteLocalRef(data);
        if (name != nullptr) env->DeleteLocalRef(name);
        if (!success) break;
    }
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(names);
    if (!success || g_game_runtime_bridge_class == nullptr
            || g_game_agent_bridge_class == nullptr) {
        log_line("failed to define the loader-local game bridge");
        return false;
    }
    log_line("loader-local AgentBridge defined in the game ClassLoader");
    return true;
}

void JNICALL on_vm_death(jvmtiEnv*, JNIEnv*) {
    g_vm_dead.store(true, std::memory_order_release);
    if (g_game_loader_event != nullptr) SetEvent(g_game_loader_event);
    if (g_reload_event != nullptr) SetEvent(g_reload_event);
}

void JNICALL on_class_file_load(
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
    if (name != nullptr && std::string(name) == kMinecraftClass) {
        capture_game_loader(env, loader);
    }
    if (!g_ready.load(std::memory_order_acquire)
            || g_inside_transform || name == nullptr
            || g_target_names.find(name) == g_target_names.end()
            || class_data == nullptr || class_data_length <= 0) {
        return;
    }

    g_inside_transform = true;
    jstring class_name = env->NewStringUTF(name);
    jbyteArray input = env->NewByteArray(class_data_length);
    if (class_name == nullptr || input == nullptr || env->ExceptionCheck()) {
        describe_and_clear(env, "transform input allocation");
        if (input != nullptr) env->DeleteLocalRef(input);
        if (class_name != nullptr) env->DeleteLocalRef(class_name);
        g_inside_transform = false;
        return;
    }
    env->SetByteArrayRegion(input, 0, class_data_length,
            reinterpret_cast<const jbyte*>(class_data));
    if (env->ExceptionCheck()) {
        describe_and_clear(env, "transform input copy");
        env->DeleteLocalRef(input);
        env->DeleteLocalRef(class_name);
        g_inside_transform = false;
        return;
    }

    const jint flags = class_being_redefined == nullptr ? 0 : 1;
    auto output = reinterpret_cast<jbyteArray>(env->CallStaticObjectMethod(
            g_bridge_class, g_transform_method, class_name, loader, input, flags));
    if (env->ExceptionCheck()) {
        describe_and_clear(env, name);
    } else if (output != nullptr) {
        const jsize output_length = env->GetArrayLength(output);
        if (output_length > 0) {
            unsigned char* allocated = nullptr;
            const jvmtiError allocation_error =
                    jvmti->Allocate(output_length, &allocated);
            if (allocation_error == JVMTI_ERROR_NONE && allocated != nullptr) {
                env->GetByteArrayRegion(output, 0, output_length,
                        reinterpret_cast<jbyte*>(allocated));
                if (env->ExceptionCheck()) {
                    describe_and_clear(env, "transform output copy");
                    jvmti->Deallocate(allocated);
                } else {
                    *new_class_data_length = output_length;
                    *new_class_data = allocated;
                }
            } else {
                log_jvmti_error("Allocate transformed class bytes", allocation_error);
            }
        }
        env->DeleteLocalRef(output);
    }
    env->DeleteLocalRef(input);
    env->DeleteLocalRef(class_name);
    g_inside_transform = false;
}

bool add_capabilities() {
    jvmtiCapabilities potential{};
    jvmtiError error = g_jvmti->GetPotentialCapabilities(&potential);
    if (error != JVMTI_ERROR_NONE) {
        log_jvmti_error("GetPotentialCapabilities", error);
        return false;
    }
    log_line(std::string("live capabilities: retransform=")
            + (potential.can_retransform_classes ? "yes" : "no")
            + " any=" + (potential.can_retransform_any_class ? "yes" : "no")
            + " all-hooks="
            + (potential.can_generate_all_class_hook_events ? "yes" : "no"));
    if (!potential.can_retransform_classes) {
        log_line("hard failure: target JVM does not offer can_retransform_classes in Live phase");
        return false;
    }

    jvmtiCapabilities requested{};
    requested.can_retransform_classes = 1;
    requested.can_retransform_any_class = potential.can_retransform_any_class;
    requested.can_generate_all_class_hook_events =
            potential.can_generate_all_class_hook_events;
    error = g_jvmti->AddCapabilities(&requested);
    if (error != JVMTI_ERROR_NONE) {
        log_jvmti_error("AddCapabilities", error);
        return false;
    }
    jvmtiCapabilities actual{};
    error = g_jvmti->GetCapabilities(&actual);
    if (error != JVMTI_ERROR_NONE || !actual.can_retransform_classes) {
        if (error != JVMTI_ERROR_NONE) log_jvmti_error("GetCapabilities", error);
        log_line("hard failure: can_retransform_classes was not granted");
        return false;
    }
    return true;
}

std::string internal_name(jvmtiEnv* jvmti, jclass klass) {
    char* signature = nullptr;
    char* generic = nullptr;
    const jvmtiError error = jvmti->GetClassSignature(klass, &signature, &generic);
    std::string result;
    if (error == JVMTI_ERROR_NONE && signature != nullptr) {
        const std::string raw(signature);
        if (raw.size() >= 2 && raw.front() == 'L' && raw.back() == ';') {
            result = raw.substr(1, raw.size() - 2);
        }
    }
    if (signature != nullptr) {
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    }
    if (generic != nullptr) {
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    }
    return result;
}

bool start_runtime(JNIEnv* env, const BridgeConfig& config) {
    if (g_runtime_started.load(std::memory_order_acquire)) return true;
    jobject game_loader = local_game_loader(env);
    if (game_loader == nullptr) return false;
    if (!define_game_bridge(env, game_loader)) {
        env->DeleteLocalRef(game_loader);
        return false;
    }
    jstring home = new_utf8_string(env, config.home);
    jstring payload = new_utf8_string(env, config.payload);
    jstring hwid = new_utf8_string(env, config.hwid);
    const jboolean started = env->CallStaticBooleanMethod(
            g_bridge_class, g_start_method, home, payload, game_loader, hwid,
            g_game_agent_bridge_class, g_game_runtime_bridge_class);
    if (env->ExceptionCheck()) describe_and_clear(env, "runtime startup");
    if (payload != nullptr) env->DeleteLocalRef(payload);
    if (hwid != nullptr) env->DeleteLocalRef(hwid);
    if (home != nullptr) env->DeleteLocalRef(home);
    env->DeleteLocalRef(game_loader);
    if (started == JNI_TRUE) {
        g_runtime_started.store(true, std::memory_order_release);
        g_ready.store(true, std::memory_order_release);
        log_line("Java runtime active");
        return true;
    }
    log_line("Java runtime did not start");
    return false;
}

std::vector<jclass> collect_loaded_targets(JNIEnv* env) {
    jint class_count = 0;
    jclass* classes = nullptr;
    const jvmtiError error = g_jvmti->GetLoadedClasses(&class_count, &classes);
    if (error != JVMTI_ERROR_NONE) {
        log_jvmti_error("GetLoadedClasses", error);
        return {};
    }
    std::vector<jclass> targets;
    for (jint index = 0; index < class_count; ++index) {
        jclass klass = classes[index];
        const std::string name = internal_name(g_jvmti, klass);
        if (name == kMinecraftClass) {
            jobject loader = nullptr;
            const jvmtiError loader_error = g_jvmti->GetClassLoader(klass, &loader);
            if (loader_error == JVMTI_ERROR_NONE && loader != nullptr) {
                capture_game_loader(env, loader);
                env->DeleteLocalRef(loader);
            }
        }
        if (g_target_names.find(name) != g_target_names.end()) {
            jboolean modifiable = JNI_FALSE;
            if (g_jvmti->IsModifiableClass(klass, &modifiable) == JVMTI_ERROR_NONE
                    && modifiable == JNI_TRUE) {
                targets.push_back(klass);
                continue;
            }
            log_line("target is not modifiable: " + name);
        }
        env->DeleteLocalRef(klass);
    }
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return targets;
}

bool find_loaded_game_loader(JNIEnv* env) {
    jint class_count = 0;
    jclass* classes = nullptr;
    const jvmtiError error = g_jvmti->GetLoadedClasses(&class_count, &classes);
    if (error != JVMTI_ERROR_NONE) return false;
    bool found = false;
    for (jint index = 0; index < class_count; ++index) {
        jclass klass = classes[index];
        if (!found && internal_name(g_jvmti, klass) == kMinecraftClass) {
            jobject loader = nullptr;
            if (g_jvmti->GetClassLoader(klass, &loader) == JVMTI_ERROR_NONE
                    && loader != nullptr) {
                found = capture_game_loader(env, loader);
                env->DeleteLocalRef(loader);
            }
        }
        env->DeleteLocalRef(klass);
    }
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return found;
}

void retransform_targets(JNIEnv* env, std::vector<jclass>& targets) {
    int transformed = 0;
    for (jclass klass : targets) {
        const std::string name = internal_name(g_jvmti, klass);
        const jvmtiError error = g_jvmti->RetransformClasses(1, &klass);
        if (error == JVMTI_ERROR_NONE) {
            ++transformed;
        } else {
            log_jvmti_error(("RetransformClasses " + name).c_str(), error);
        }
        env->DeleteLocalRef(klass);
    }
    targets.clear();
    log_line("initial retransformation complete: " + std::to_string(transformed)
            + " target classes");
}

bool initialize_jvmti(JNIEnv* env, const BridgeConfig& config) {
    const jint jvmti_result = g_vm->GetEnv(
            reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2);
    if (jvmti_result != JNI_OK || g_jvmti == nullptr) {
        log_line("GetEnv(JVMTI_VERSION_1_2) failed: "
                + std::to_string(jvmti_result));
        return false;
    }
    jvmtiPhase phase = JVMTI_PHASE_DEAD;
    const jvmtiError phase_error = g_jvmti->GetPhase(&phase);
    if (phase_error != JVMTI_ERROR_NONE || phase != JVMTI_PHASE_LIVE) {
        if (phase_error != JVMTI_ERROR_NONE) log_jvmti_error("GetPhase", phase_error);
        log_line("target JVM is not in Live phase: "
                + std::to_string(static_cast<int>(phase)));
        return false;
    }
    const jvmtiError bootstrap_error =
            g_jvmti->AddToBootstrapClassLoaderSearch(config.bootstrap.c_str());
    if (bootstrap_error != JVMTI_ERROR_NONE) {
        log_jvmti_error("AddToBootstrapClassLoaderSearch", bootstrap_error);
        return false;
    }
    if (!config.name.empty()) {
        jclass system = env->FindClass("java/lang/System");
        jmethodID set_property = system == nullptr ? nullptr : env->GetStaticMethodID(
                system, "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        jstring key = set_property == nullptr ? nullptr
                : env->NewStringUTF("moons.name");
        jstring value = key == nullptr ? nullptr : new_utf8_string(env, config.name);
        if (value != nullptr) {
            jobject previous = env->CallStaticObjectMethod(system, set_property, key, value);
            if (previous != nullptr) env->DeleteLocalRef(previous);
        }
        if (value != nullptr) env->DeleteLocalRef(value);
        if (key != nullptr) env->DeleteLocalRef(key);
        if (system != nullptr) env->DeleteLocalRef(system);
        if (env->ExceptionCheck()) {
            describe_and_clear(env, "System.setProperty(moons.name)");
            return false;
        }
    }
    if (!load_java_bridge(env, config.payload)) {
        log_line("NativeTransformerBridge initialization failed");
        return false;
    }
    if (!add_capabilities()) return false;

    g_game_loader_event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (g_game_loader_event == nullptr) {
        log_line("CreateEvent for game loader failed: "
                + std::to_string(GetLastError()));
        return false;
    }
    g_reload_event = CreateEventW(
            nullptr, FALSE, FALSE, reload_event_name().c_str());
    if (g_reload_event == nullptr) {
        log_line("CreateEvent for runtime reload failed: "
                + std::to_string(GetLastError()));
        return false;
    }
    jvmtiEventCallbacks callbacks{};
    callbacks.ClassFileLoadHook = &on_class_file_load;
    callbacks.VMDeath = &on_vm_death;
    jvmtiError error = g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (error != JVMTI_ERROR_NONE) {
        log_jvmti_error("SetEventCallbacks", error);
        return false;
    }
    error = g_jvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, nullptr);
    if (error != JVMTI_ERROR_NONE) {
        log_jvmti_error("Enable VMDeath", error);
        return false;
    }
    error = g_jvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
    if (error != JVMTI_ERROR_NONE) {
        log_jvmti_error("Enable ClassFileLoadHook", error);
        return false;
    }
    log_line("JVMTI ClassFileLoadHook active; targets="
            + std::to_string(g_target_names.size()));

    find_loaded_game_loader(env);
    if (start_runtime(env, config)) {
        Sleep(100);
        std::vector<jclass> targets = collect_loaded_targets(env);
        retransform_targets(env, targets);
    }
    return true;
}

DWORD WINAPI worker(LPVOID) {
    const BridgeConfig config = read_config();
    set_attempt(config.attempt);
    if (config.payload.empty() || config.bootstrap.empty() || config.home.empty()) {
        log_line("invalid one-shot config: payload/bootstrap/home are required");
        return 1;
    }

    wchar_t image[32768] = {};
    const DWORD length = GetModuleFileNameW(nullptr, image, 32768);
    log_line("begin: pid=" + std::to_string(GetCurrentProcessId())
            + " attempt=" + (config.attempt.empty() ? "?" : config.attempt)
            + " exe=" + (length > 0
                    ? wide_to_utf8(std::wstring(image, length)) : "?"));

    JavaVM* vm = nullptr;
    jsize count = 0;
    for (int attempt = 0; attempt < 300; ++attempt) {
        const jint result = JNI_GetCreatedJavaVMs(&vm, 1, &count);
        if (result == JNI_OK && count > 0 && vm != nullptr) break;
        Sleep(100);
    }
    if (vm == nullptr) {
        log_line("JNI_GetCreatedJavaVMs found no VM within 30 seconds");
        return 2;
    }
    g_vm = vm;
    JNIEnv* env = nullptr;
    const jint attach_result = vm->AttachCurrentThreadAsDaemon(
            reinterpret_cast<void**>(&env), nullptr);
    if (attach_result != JNI_OK || env == nullptr) {
        log_line("AttachCurrentThreadAsDaemon failed: "
                + std::to_string(attach_result));
        return 3;
    }
    if (!initialize_jvmti(env, config)) {
        vm->DetachCurrentThread();
        log_line("bridge failed during JVMTI initialization");
        return 4;
    }

    while (!g_vm_dead.load(std::memory_order_acquire)
            && !g_runtime_started.load(std::memory_order_acquire)) {
        jobject known_loader = local_game_loader(env);
        if (known_loader != nullptr) {
            env->DeleteLocalRef(known_loader);
            if (start_runtime(env, config)) {
                Sleep(100);
                std::vector<jclass> targets = collect_loaded_targets(env);
                retransform_targets(env, targets);
                break;
            }
        }
        WaitForSingleObject(g_game_loader_event, 500);
        if (!g_runtime_started.load(std::memory_order_acquire)) {
            find_loaded_game_loader(env);
        }
    }
    if (g_runtime_started.load(std::memory_order_acquire)
            && g_reload_event != nullptr) {
        while (!g_vm_dead.load(std::memory_order_acquire)) {
            const DWORD wait_result = WaitForSingleObject(g_reload_event, INFINITE);
            if (g_vm_dead.load(std::memory_order_acquire)) break;
            if (wait_result != WAIT_OBJECT_0) {
                log_line("runtime reload wait failed: " + std::to_string(GetLastError()));
                break;
            }

            const BridgeConfig reload = read_config();
            set_attempt(reload.attempt);
            if (reload.payload.empty() || reload.bootstrap.empty() || reload.home.empty()) {
                log_line("invalid reload config: payload/bootstrap/home are required");
                continue;
            }

            g_runtime_started.store(false, std::memory_order_release);
            if (start_runtime(env, reload)) {
                log_line("Java runtime reload complete");
            } else {
                log_line("Java runtime reload failed; waiting for another request");
            }
        }
    }
    vm->DetachCurrentThread();
    if (g_reload_event != nullptr) {
        CloseHandle(g_reload_event);
        g_reload_event = nullptr;
    }
    if (g_game_loader_event != nullptr) {
        CloseHandle(g_game_loader_event);
        g_game_loader_event = nullptr;
    }
    log_line(g_vm_dead.load(std::memory_order_acquire)
            ? "target VM ended"
            : "bridge worker stopped");
    return 0;
}

}  // namespace

BOOL WINAPI DllMain(HINSTANCE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
        HANDLE thread = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
        if (thread != nullptr) CloseHandle(thread);
    }
    return TRUE;
}
