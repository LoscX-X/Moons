# Moons native startup-agent test transport

This directory contains a test-only thin JVMTI transport. It is loaded through
the standard `-agentpath` JVM option to verify startup-phase JVMTI/JNI/ASM
integration. It is not a public load method, does not implement process
load, and does not bypass Attach.

The native side owns only four responsibilities:

1. receive `Agent_OnLoad` and obtain `jvmtiEnv`;
2. register `VMInit` and `ClassFileLoadHook`;
3. copy selected class bytes into a Java `byte[]` through JNI;
4. return a Java ASM result in JVMTI-allocated memory.

Minecraft mappings and transformations remain in
`NativeTransformerBridge` and the existing Java `MoonsTransformer`.

## Build and verification

On Windows with the Visual Studio C++ tools and a JDK installed:

```powershell
gradle build --console=plain
```

The build discovers CMake from PATH or the Visual Studio installation and stages
these test artifacts below `build/moons-test/<version>/native-agent`:

- `moons-native.dll`
- `moons-api.jar`

The transformer JAR remains the separately classified Legacy Agent artifact at
`build/dist/legacy/<version>/moons.jar`; verification consumes it as test input.

`verifyNativeAgent` proves the generic JVMTI/JNI/ASM byte round trip.
`verifyNativeMoonsTransformer` additionally proves that the production Moons
tick transformer reaches `AgentBridge`.

## Manual verification form

PowerShell requires the complete `-agentpath` argument to be quoted because its
options are separated by semicolons:

```powershell
java '-agentpath:C:\path\moons-native.dll=jar=C:\path\moons.jar;bootstrap=C:\path\moons-api.jar;include=net/minecraft/,com/mojang/,net/caffeinemc/' -cp app.jar example.Main
```

Options:

- `jar` (required): JAR containing the Java transformer bridge and ASM.
- `bootstrap` (optional): bootstrap-visible API JAR used by loaded hooks.
- `bridge` (optional): Java bridge class; defaults to the production bridge.
- `include` (optional): comma-separated internal-name prefixes filtered before
  crossing JNI.

The current native path demonstrates startup transformation and the production
hook bridge. Runtime/module bootstrap is still owned by the Java Agent path and
is not duplicated in C++.
