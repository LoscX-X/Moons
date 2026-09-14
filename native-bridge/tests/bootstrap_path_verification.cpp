#include "bootstrap_search.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <iostream>
#include <string>

namespace {
void log_line(const std::string& line) {
    std::cout << line << '\n';
}
}

// Each invocation owns a fresh JVM, so another test cannot mask a missing JAR.
int wmain(int argc, wchar_t** argv) {
    if (argc < 3) {
        std::cerr << "Usage: bootstrap-path-verification <jar> <ok|rejected> [raw]\n";
        return 2;
    }
    const int length = static_cast<int>(wcslen(argv[1]));
    const int size = WideCharToMultiByte(CP_UTF8, 0, argv[1], length,
            nullptr, 0, nullptr, nullptr);
    std::string path(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, argv[1], length, path.data(), size, nullptr, nullptr);

    JavaVMOption options[2]{};
    options[0].optionString = const_cast<char*>("-Xcheck:jni");
    options[1].optionString = const_cast<char*>("-Xshare:off");
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8;
    args.nOptions = 2;
    args.options = options;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) return 3;
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) return 4;
    jvmtiPhase phase{};
    if (jvmti->GetPhase(&phase) != JVMTI_ERROR_NONE || phase != JVMTI_PHASE_LIVE) return 5;

    const jvmtiError error = argc > 3 && std::wstring(argv[3]) == L"raw"
            ? jvmti->AddToBootstrapClassLoaderSearch(path.c_str())
            : moons::add_bootstrap_jar(env, jvmti, path, log_line);
    std::cout << "ACP=" << GetACP() << " JVMTI=" << error << '\n';
    bool passed = std::wstring(argv[2]) == L"rejected"
            ? error == JVMTI_ERROR_ILLEGAL_ARGUMENT : error == JVMTI_ERROR_NONE;
    if (passed && error == JVMTI_ERROR_NONE) {
        jclass probe = env->FindClass("moons/test/BootstrapPathProbe");
        if (probe == nullptr || env->ExceptionCheck()) {
            passed = false;
        } else {
            jobject loader = nullptr;
            passed = jvmti->GetClassLoader(probe, &loader) == JVMTI_ERROR_NONE
                    && loader == nullptr;
            if (loader != nullptr) env->DeleteLocalRef(loader);
            jmethodID value = env->GetStaticMethodID(probe, "value", "()I");
            if (value == nullptr || env->ExceptionCheck()) {
                passed = false;
            } else {
                const jint actual = env->CallStaticIntMethod(probe, value);
                passed = !env->ExceptionCheck() && actual == 42 && passed;
            }
            env->DeleteLocalRef(probe);
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        passed = false;
    }
    vm->DestroyJavaVM();
    std::cout << (passed ? "PASS" : "FAIL") << '\n';
    return passed ? 0 : 1;
}
