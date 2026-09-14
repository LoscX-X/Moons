#include "bootstrap_search.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <utility>
#include <vector>

namespace moons {
namespace {

bool decode_utf8(const std::string& value, std::wstring& wide) {
    if (value.empty() || value.find('\0') != std::string::npos) return false;
    const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
            value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (size <= 0) return false;
    wide.resize(static_cast<std::size_t>(size));
    return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
            static_cast<int>(value.size()), wide.data(), size) == size;
}

bool native_path(const std::wstring& wide, std::string& encoded) {
    const UINT code_page = GetACP();
    // UTF-8 forbids lpUsedDefaultChar and WC_NO_BEST_FIT_CHARS.
    const DWORD flags = code_page == CP_UTF8 ? WC_ERR_INVALID_CHARS
                                           : WC_NO_BEST_FIT_CHARS;
    BOOL substituted = FALSE;
    BOOL* used_default = code_page == CP_UTF8 ? nullptr : &substituted;
    const int size = WideCharToMultiByte(code_page, flags, wide.data(),
            static_cast<int>(wide.size()), nullptr, 0, nullptr, used_default);
    if (size <= 0 || substituted) return false;
    encoded.resize(static_cast<std::size_t>(size));
    if (WideCharToMultiByte(code_page, flags, wide.data(),
            static_cast<int>(wide.size()), encoded.data(), size,
            nullptr, used_default) != size || substituted) return false;

    // Never let a lossy conversion select a different file.
    const int decoded_size = MultiByteToWideChar(code_page, MB_ERR_INVALID_CHARS,
            encoded.data(), size, nullptr, 0);
    if (decoded_size != static_cast<int>(wide.size())) return false;
    std::wstring decoded(wide.size(), L'\0');
    return MultiByteToWideChar(code_page, MB_ERR_INVALID_CHARS,
            encoded.data(), size, decoded.data(), decoded_size) == decoded_size
            && decoded == wide;
}

}  // namespace

jvmtiError add_bootstrap_jar(JNIEnv* env, jvmtiEnv* jvmti,
        const std::string& utf8_path, void (*log)(const std::string&)) {
    log("bootstrap JAR: " + utf8_path);
    std::wstring wide;
    if (!decode_utf8(utf8_path, wide)) {
        log("bootstrap path is not valid UTF-8");
        return JVMTI_ERROR_ILLEGAL_ARGUMENT;
    }
    HANDLE file = CreateFileW(wide.c_str(), GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        const DWORD error = GetLastError();
        log("bootstrap JAR cannot be opened: Win32 error " + std::to_string(error));
        return JVMTI_ERROR_ILLEGAL_ARGUMENT;
    }
    LARGE_INTEGER size{};
    const BOOL sized = GetFileSizeEx(file, &size);
    const DWORD size_error = sized ? ERROR_SUCCESS : GetLastError();
    CloseHandle(file);
    if (!sized || size.QuadPart == 0) {
        log("bootstrap JAR is empty or unreadable: Win32 error "
                + std::to_string(size_error));
        return JVMTI_ERROR_ILLEGAL_ARGUMENT;
    }

    std::vector<std::pair<std::string, std::string>> candidates;
    std::string encoded;
    // HotSpot's Windows os::stat/open decode this argument with CP_ACP,
    // despite JVMTI specifying modified UTF-8. Keep config/JNI strings UTF-8;
    // adapt only this filesystem boundary inside the target JVM process.
    if (native_path(wide, encoded)) {
        candidates.emplace_back(encoded, "Windows code page " + std::to_string(GetACP()));
    } else {
        log("bootstrap path cannot be represented losslessly in Windows code page "
                + std::to_string(GetACP()));
        // An existing 8.3 alias can also cover characters outside the code page.
        // Do not assume short names are enabled or always contain only ASCII.
        const DWORD capacity = GetShortPathNameW(wide.c_str(), nullptr, 0);
        if (capacity > 0) {
            std::wstring short_path(capacity, L'\0');
            const DWORD length = GetShortPathNameW(
                    wide.c_str(), short_path.data(), capacity);
            if (length > 0 && length < capacity) {
                short_path.resize(length);
                if (native_path(short_path, encoded)) {
                    candidates.emplace_back(encoded, "Windows short path");
                }
            }
        }
    }

    // Preserve support for JVMs implementing the specified encoding, including
    // supplementary Unicode characters (JNI uses modified UTF-8, not UTF-8).
    jstring java_path = env->NewString(reinterpret_cast<const jchar*>(wide.data()),
            static_cast<jsize>(wide.size()));
    if (java_path == nullptr) return JVMTI_ERROR_OUT_OF_MEMORY;
    const char* modified = env->GetStringUTFChars(java_path, nullptr);
    if (modified == nullptr) {
        env->DeleteLocalRef(java_path);
        return JVMTI_ERROR_OUT_OF_MEMORY;
    }
    encoded = modified;
    env->ReleaseStringUTFChars(java_path, modified);
    env->DeleteLocalRef(java_path);
    if (candidates.empty() || candidates.front().first != encoded) {
        candidates.emplace_back(encoded, "modified UTF-8");
    }
    // UTF-8 Windows code pages use standard UTF-8 even for supplementary chars.
    if (utf8_path != encoded
            && (candidates.empty() || candidates.front().first != utf8_path)) {
        candidates.emplace_back(utf8_path, "UTF-8");
    }

    for (const auto& candidate : candidates) {
        const jvmtiError error = jvmti->AddToBootstrapClassLoaderSearch(
                candidate.first.c_str());
        if (error == JVMTI_ERROR_NONE) {
            log("bootstrap search added using " + candidate.second);
            return error;
        }
        log("bootstrap search rejected " + candidate.second
                + ": JVMTI error " + std::to_string(static_cast<int>(error)));
        if (error != JVMTI_ERROR_ILLEGAL_ARGUMENT) return error;
    }
    return JVMTI_ERROR_ILLEGAL_ARGUMENT;
}

}  // namespace moons
