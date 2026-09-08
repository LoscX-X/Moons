# Native Startup-Agent Tests

[简体中文](README.zh-CN.md) | **English**

This directory provides a JVMTI agent for verifying bytecode transformation during JVM startup. It is loaded through the JVM's `-agentpath` option. The production launcher's runtime loading bridge is in [native-bridge](../native-bridge/).

## Responsibilities

The native layer receives `Agent_OnLoad`, registers `VMInit` and `ClassFileLoadHook`, passes class bytes to the Java transformer through JNI, and copies the result into JVMTI-allocated memory.

`NativeTransformerBridge` and `MoonsTransformer` handle Minecraft mappings and bytecode transformation. The Java layer handles runtime and module initialization.

## Build and Verification

Run the following command from the repository root. Requirements are the same as those in the [main README](../readme.md#requirements). These tasks run only on Windows:

```powershell
.\gradlew.bat verifyNativeAgent verifyNativeMoonsTransformer
```

| Task | Coverage |
|---|---|
| `verifyNativeAgent` | The JVMTI → JNI → Java ASM → JVMTI bytecode transformation path |
| `verifyNativeMoonsTransformer` | The production tick transformer and its calls to `AgentBridge` |

Minecraft 26.1.2 is selected by default. Add `'-Pminecraft_version=26.2'` to verify 26.2. The tasks build their test dependencies and configure JVM arguments automatically.

Test files are staged under `build/moons-test/<version>/native-agent/`:

- `moons-native.dll`: the startup agent.
- `moons-api.jar`: the API used by transformed hooks, prepared by the production-transformer verification task.

`<version>` is either `26_1` or `26_2`. Production-transformer verification uses `build/dist/agent/<version>/moons.jar` as input.

## Agent Options

Options are separated by semicolons. When configuring them manually in PowerShell, quote the complete `-agentpath:...` argument.

| Option | Description |
|---|---|
| `jar` | Required. JAR containing the Java transformer bridge and ASM. |
| `bootstrap` | Optional. API JAR made available to the bootstrap class loader. |
| `bridge` | Optional. Java transformer bridge class name; defaults to the production bridge. |
| `include` | Optional. Comma-separated internal class-name prefixes, filtered before crossing JNI. |

Build and verification tasks are defined in [gradle/native.gradle](../gradle/native.gradle).
