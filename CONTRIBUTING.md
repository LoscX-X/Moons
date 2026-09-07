# 开发与维护

Moons 的维护目标是让功能的行为、线程和资源所有者保持清楚。目录整理和格式化不应改变模块顺序、配置键、数据包时序或版本既有策略。

## 开发环境

- 使用 x64 JDK 25，通过本机 `JAVA_HOME` 指向其安装目录。不要把个人 JDK 路径提交到 `gradle.properties`。
- 使用仓库的 Gradle Wrapper，固定版本为 9.5.1。Windows PowerShell 使用 `./gradlew.bat`，其他 shell 使用 `./gradlew`；不需要单独安装 Gradle。
- Windows 原生桥接检查与发行构建需要 Visual Studio C++ 和 CMake。非 Windows 环境跳过仅适用于 Windows 的原生验证，不能据此宣称 Windows 桥接已通过。
- 默认 Minecraft 版本为 `26.1.2`，版本目录名为 `26_1`；另一受支持版本为 `26.2`，目录名为 `26_2`。

从仓库根目录运行：

```powershell
./gradlew.bat formatCode
./gradlew.bat check
```

`check` 检查所选版本，包括源码边界、格式、功能元数据、客户端验证程序、生命周期、真实 Minecraft JAR 的字节码转换，以及当前平台适用的原生桥接验证。验证程序主要是带 `main` 的 Java 程序，`test` 允许未发现 JUnit 测试；单独运行 `test` 不能替代这些验证。

## 按变更范围验证

局部改动运行与行为相关的最少检查即可，例如：

```powershell
./gradlew.bat verifyClientEvents
./gradlew.bat verifyScaffoldRotations verifyScaffoldTrace
```

修改共享代码、版本桥或构建配置后，在变更稳定时运行一次：

```powershell
./gradlew.bat checkAllVersions
```

这个任务顺序检查 26.1.2 和 26.2，将输出分别写到 `build/checks/26_1` 和 `build/checks/26_2`，避免两个版本覆盖编译及原生产物。已完成双版本检查时，不必再追加等价的默认 `check`。只有新修改、失败或具体未解决的问题才需要重复检查。纯文档改动不需要游戏构建。

Windows 下还会执行一次 `verifyHostUtilities`：编译启动器和 HWID 入口，仅运行摘要、元数据、ZIP 和暂存文件的工具验证，不启动加载器或查询硬件。宿主工具的局部改动可以单独运行该任务；它需要 .NET Framework 4.x 的 C# 编译器。

针对单版本问题可运行 `./gradlew.bat checkMinecraft26_2`，使用同样的独立输出目录。GUI、GPU 资源或世界交互的实际表现无法仅由这些无游戏验证覆盖；改动涉及它们时，补充对应场景的人工检查，并说明实际检查了什么。

## 代码风格

- Java 使用 `google-java-format 1.34.0` 的 AOSP 风格，缩进为四空格。现有长字符串与 Javadoc 不由格式任务重排。
- Kotlin 使用 `ktfmt 0.63` 的 `kotlinlang` 风格。
- `formatCode` 应用 Java、Kotlin 和文本格式；`checkCodeStyle` 只检查，不改文件。格式器仅为构建依赖，不进入 Minecraft 运行产物。
- 其他源码与脚本遵循 [.editorconfig](.editorconfig) 的缩进、编码及换行约定。不要夹带无关的改名、配置迁移或行为调整。
- 类和方法按职责命名；避免用 `Legacy`、版本号或无具体含义的 `Helper` 掩盖归属。注释说明前提、时序或保留某种实现的原因，不重复代码。
- 大型功能按观察、选择、准备、执行、恢复等实际边界拆分；复用的计算进入 `utils`，跨阶段状态由明确的所有者管理。不要只为了增加类数拆分代码。

## 源码和版本边界

先阅读 [客户端结构](docs/client-architecture.md) 和 [Minecraft 版本边界](docs/MC_VERSION_BOUNDARIES.md)。相同业务逻辑放在 `src/minecraft/shared`，通过现有版本访问桥处理 API 差异。`shared` 可以引用受支持版本共同提供的 Minecraft 类型。

`src/bootstrap` 不得导入 Minecraft、客户端或功能类。`verifySourceLayout` 检查此边界，以及共享目录与任一版本目录中的重复源码身份。不能同时在共享目录和所选版本目录定义同包同文件名的源码。

修改版本实现时保留已有方法签名、调用顺序和策略；相似实现不一定等价。新增版本只添加确实不同的访问桥、渲染实现和精确注入映射，不要复制整套共享模块。

## 生命周期、线程和状态

- 注册监听器时保留稳定的 `Class.phase` 标签，保持现有优先级和同优先级注册顺序。订阅交由活动模块的 `ResourceScope` 持有，不在注册点立即关闭。
- 资源在实际所有者处申请和释放。卸载关闭作用域；`DefaultResourceScope` 支持重复关闭并按逆申请顺序清理。新增线程池、网络客户端、GPU 缓冲区或租约也需要明确退出路径。
- 事件线程标记是约定，不会自动切换线程。网络接收 PRE 只排队或取消，避免访问渲染状态与非线程安全的世界集合；客户端状态更新遵循 APPLY 边界。
- 异步结果在客户端线程应用前，核对原连接、世界或批次仍然有效。单次处理先捕获 `player`、`level`、`connection` 等上下文，检查后使用同一对象。
- 渲染资源在所属渲染生命周期内使用和释放。退出世界、关闭模块和运行时卸载是不同边界，不要互相代替。
- 旋转、物品栏和输入协调使用现有租约及管理入口，保留申请、提交、释放和恢复顺序。相关约定见 [静默旋转模板](docs/SILENT_ROTATION_TEMPLATE.md)。

## 增加验证程序

客户端验证放入 `src/test/java`。在 [gradle/verification.gradle](gradle/verification.gradle) 的 `clientVerifications` map 增加一个任务：

```groovy
verifyFeatureBehavior: [
        mainClass: "com.blanoir.moons.client.example.FeatureBehaviorVerification",
        description: "Verifies the feature's observable behavior."
]
```

map 条目自动注册为 `JavaExec` 并接入 `check`；特殊参数在任务注册后单独配置。验证可观察行为和关键不变量，不要写仅重复实现的断言。优先在现有验证入口补充相关场景。

加载器夹具放在 `src/test/loader/java`，使用 `loaderTest` classpath。它包含假的 Minecraft 类，必须与依赖真实游戏类的客户端验证隔离；相应任务仍在同一个验证脚本中显式注册并接入 `check`。

## 构建脚本与文档

`build.gradle` 保留插件和共享构建配置。`gradle/` 按 Java 约定、验证、源码边界、代码风格、产物、原生构建和发行打包划分职责。新增任务放到对应脚本，不恢复单文件堆积。

变更说明写清触发场景、最终行为和实际执行的验证。更新现行文档中的路径与约定；历史审计保留当时结论，以历史标记区分，不把旧行号当作当前证据。
