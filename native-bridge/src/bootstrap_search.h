#pragma once

#include <jni.h>
#include <jvmti.h>

#include <string>

namespace moons {

// Configuration paths are UTF-8; Windows JVM filesystem calls may use CP_ACP.
jvmtiError add_bootstrap_jar(JNIEnv* env, jvmtiEnv* jvmti,
        const std::string& utf8_path, void (*log)(const std::string&));

}  // namespace moons
