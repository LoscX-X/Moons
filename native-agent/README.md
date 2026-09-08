# 原生启动代理测试

此目录提供用于验证启动阶段字节码转换的 JVMTI 代理，通过 JVM 的 `-agentpath` 参数加载。正式启动器的运行时加载桥接位于 [native-bridge](../native-bridge/)。

## 职责

原生层接收 `Agent_OnLoad`，注册 `VMInit` 和 `ClassFileLoadHook`，通过 JNI 将类字节码交给 Java 转换器，再将结果复制到 JVMTI 分配的内存。

Minecraft 映射和字节码转换由 `NativeTransformerBridge` 与 `MoonsTransformer` 负责；运行时和模块初始化由 Java 层负责。

## 构建与验证

在仓库根目录执行。环境要求与 [主 README](../readme.md#环境要求) 相同，以下任务仅在 Windows 上运行：

```powershell
.\gradlew.bat verifyNativeAgent verifyNativeMoonsTransformer
```

| 任务 | 验证内容 |
|---|---|
| `verifyNativeAgent` | JVMTI → JNI → Java ASM → JVMTI 的字节码转换链路 |
| `verifyNativeMoonsTransformer` | 正式 tick 转换器及其到 `AgentBridge` 的调用链路 |

默认验证 Minecraft 26.1.2；验证 26.2 时追加 `'-Pminecraft_version=26.2'`。任务会自动构建测试依赖并配置 JVM 参数。

测试文件位于 `build/moons-test/<version>/native-agent/`：

- `moons-native.dll`：启动代理。
- `moons-api.jar`：供转换后 Hook 使用的 API，由正式转换器验证任务准备。

`<version>` 为 `26_1` 或 `26_2`。正式转换器验证使用 `build/dist/agent/<version>/moons.jar` 作为输入。

## 代理参数

手动配置时，参数以分号分隔。PowerShell 中须将完整的 `-agentpath:...` 参数放在引号内。

| 参数 | 说明 |
|---|---|
| `jar` | 必填，包含 Java 转换桥及 ASM 的 JAR |
| `bootstrap` | 可选，向引导类加载器提供 API JAR |
| `bridge` | 可选，Java 转换桥类名，默认使用正式桥接类 |
| `include` | 可选，以逗号分隔的类内部名称前缀，在进入 JNI 前过滤 |

构建与验证任务定义见 [gradle/native.gradle](../gradle/native.gradle)。
