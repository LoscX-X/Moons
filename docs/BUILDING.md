# 构建与更新

在仓库根目录运行 PowerShell。当前构建目标为 Minecraft **26.1.2、26.2、26.3、26.4-snapshot-1**；26.3 仅支持正式版，26.4 目前仅支持此快照。

仓库默认主分支为 `latest`，现代版本的自动打包使用该分支；Minecraft 1.8.9 使用独立的 `legacy/1.8.9` 分支。

## 环境

- Windows x64，JDK 25 或更高版本；`JAVA_HOME` 指向 JDK。
- Visual Studio / Build Tools 的 C++ 桌面开发组件、Windows SDK 和 CMake 工具，用于 JNI 桥接 DLL。
- .NET Framework 4.x 的 `csc.exe`，用于启动器。构建脚本自动查找 Windows 自带的位置。
- 首次构建需要联网下载 Gradle 和依赖；缓存完整后才使用 `--offline`。

## 日常构建

```powershell
Set-Location E:\McEnv\moons
.\gradlew.bat moonsPackages
```

产物是 `build\dist\moon-install.exe` 和 `build\dist\moon.exe`。先运行安装器安装/更新 UI 运行库、共享 YSM 库及所有游戏版本适配模块，再运行加载器。加载器只校验已安装依赖，缺失、损坏或版本不匹配时提示运行匹配的安装器。

安装器内置 UI runtime、共享 YSM 库和各版本适配模块，支持离线安装和更新。

| 命令 | `build\dist` 下的产物 |
| --- | --- |
| `moonsExe` | `moon.exe`，校验依赖并加载游戏 |
| `moonsInstallExe` | `moon-install.exe`，安装/更新依赖，不加载游戏 |
| `moonsPackages` | 安装器、加载器、UI 运行库、发行锁和离线留档 ZIP |
| `moonsReleaseLock` | `moons-release-lock.json`、完整 SHA-256 文件与 `releases\<lock SHA-256>\` 冻结快照 |
| `moonsReleaseArchive` | `moons-release-archive.zip`，包含匹配的 EXE、四版原始载荷及发行锁 |
| `moonsUiRuntime` | `dependencies\moons-ui-runtime.jar` 及 SHA-256 文件 |
| `ysmAllVersions` | `moons-ysm-all.zip` 和四个单版本 ZIP |
| `ysmBundle -Pminecraft_version=26.2` | `moons-ysm-26.2.zip` |
| `moonsJar -Pminecraft_version=26.2` | `agent\26_2\moons.jar`，由加载器加载 |

## 客户端与依赖版本

四个维护目标由 `config/version-catalog.json` 统一声明，生成构建任务、启动器版本选择和资源索引。退役版登记为 legacy 并引用完整发行 lock 哈希，不使用当前工具链重编译；尚未实现的 special 不生成可用选项。发行锁记录实际制品的完整 SHA-256、大小、来源及依赖闭包；本地快照按完整 lock 哈希保存并校验，已有快照不会被新构建覆盖。载荷补丁重建的 ZIP 序列化可能不同于原始 JAR，因此原始制品哈希与加载器的重建配方分别记录。

安装器发布 `MOONS_HOME/installations/<依赖 SHA-256>/` 下的库、适配模块和各 profile 上下文。生产加载器选择游戏版本后，只检查该 profile 的 UI、三个 YSM 库及一个适配模块；把上下文路径和 SHA-256 交给 native/Java，运行时再次校验，不通过全局 current 指针选库。模块和模型数据仍使用原 `MOONS_HOME/data`。

- `gradle.properties` 的 `load_version` 是客户端版本，必须使用 `X.Y.Z.x-Experiment` 或 `X.Y.Z.x-Release`。前三段是发行基线，第四段 x 是补丁顺序。本地 Gradle 和 Actions 拒绝其他后缀、三段数字或缺少后缀的版本。
- 首轮发布 `1.0.0.0-Experiment`；以后每次 push 前默认将 x 加 1，前三段变化时将 x 重置为 0。同一次 push 重新运行构建不增加 x，构建流程不会反向提交版本改动。Windows 文件版本使用完整四段数字。
- `Experiment` 发布为预发布版，不覆盖 GitHub 的 Latest；稳定后使用 `Release` 发布为正式版并标记 Latest。版本号和提交 SHA 一起标识本次构建，旧版冻结制品的版本名保持不变。
- 依赖版本由 UI 和全部 YSM 文件的哈希生成，界面显示前 12 位。
- 两个 EXE 显示客户端/依赖版本，Windows 文件属性包含客户端版本，`--version` 输出详细元数据。
- 每个游戏版本的依赖清单和版本记录位于其独立依赖包中。
- CI 以 `GITHUB_SHA` 标识构建，本地默认为 `local`，可用 `-Pmoons_build_id=<id>` 指定。
- CI 摘要和构建日志显示客户端版本、实际检出的 commit；打包后补充依赖、UI 和 YSM 编号。Release 标题包含版本和短 commit，说明及 `moons-build-info` 产物保留完整构建信息。

## CI 文件命名与按需更新

自动预发布版本保留最近 5 个，当前运行发布的版本、正式版本、草稿和不可变版本保留。Actions 构建附件保留 7 天。

CI 下载附件和 Release 中的 EXE 命名为 `moon-<load_version>-<8位commit>.exe`、`moon-install-<load_version>-<8位commit>.exe`。YSM 合集为 `moons-ysm-all.zip`。

依赖版本不变且已安装文件完好时，只需更新加载器。依赖内容变化或文件损坏时，运行匹配的安装器。

## 构建后运行

运行匹配的安装器，选择游戏版本安装，再启动加载器。

外部扩展模块可通过模块重载更新；安装器管理的 YSM 使用固定依赖包。修改 bootstrap API、宿主渲染钩子或注入位置后，需要重新构建 EXE、重启游戏并重新注入。

查看构建产物路径和时间，并运行加载器：

```powershell
Get-Item .\build\dist\moon.exe | Select-Object FullName, LastWriteTime, Length
& .\build\dist\moon.exe
```

## 按需验证

`check` 默认只覆盖生产编译、Minecraft 注入点与私有成员、YSM 版本适配，以及加载、安装更新和缓存边界。功能回归和共享 YSM 核心验证按修改范围显式运行，不再随 `check` / `checkAllVersions` 自动执行，也不会在默认检查中编译整套功能验证源码。

```powershell
# 仅编译全部版本的正式源码
.\gradlew.bat compileAllVersions

# 全部版本：编译、注入点、版本适配及加载/更新/缓存边界
.\gradlew.bat checkAllVersions

# 局部修改只跑相关检查
.\gradlew.bat verifyYsmCore verifyYsmRenderSetup '-Pminecraft_version=26.3'
.\gradlew.bat check '-Pminecraft_version=26.4-snapshot-1'
.\gradlew.bat verifyYsmCore '-Pysm_test_model=C:\Models\example.ysm'
.\gradlew.bat verifyYsmPackage
.\gradlew.bat verifyLauncherPackages # 隔离目录验证两个真实 EXE，不注入游戏
.\gradlew.bat verifyRasterPipeline # 共享光栅流程的像素、缓存和资源生命周期检查
.\gradlew.bat verifyTaskScope # 后台任务取消、真实终止和旧回调隔离
.\gradlew.bat verifyRemoteConfig # 远程连接代次、命令重试与有界排队
.\gradlew.bat verifySettingsPersistence # 写入失败保留 dirty 版本与重试
.\gradlew.bat verifyRuntimeStartup # 首 tick、核心失败及资源清理
.\gradlew.bat verifyDependencyContexts # A/B 共存、旧 EXE 视图与 C#/Java 上下文
.\gradlew.bat verifyAutoTotem verifyDamagePrediction # 修改图腾或伤害预测时
.\gradlew.bat verifyHitSelect verifyMisplace # 修改攻击时序或位置预测时
.\gradlew.bat benchmarkYsm
```

`verifyYsmCore` 使用所选 Minecraft 的 Java 依赖运行一次核心验证，包括头部追踪、第一人称过滤、动画和队列快照。`verifyYsmRenderSetup` 检查该版本的材质与顶点变换；`benchmarkYsm` 测 CPU 时间和分配量，不代表游戏 FPS。最终视觉效果仍需进游戏确认第三人称抬头/低头、第一人称双臂、持物、透明材质和动作切换。

CI 在 push、PR 和默认的手动运行中先执行 `checkAllVersions`，通过后再打包。该任务已经包含各版本的注入点检查。手动运行时可取消勾选 `verify` 来跳过验证。使用 `formatCode` 格式化代码。

## Windows bootstrap 中文路径验证

桥接配置使用 UTF-8，日志记录 bootstrap JAR 路径及加载时采用的编码。系统代码页无法表示的字符需要短文件名或 JVM 的 Unicode 路径支持。

在已配置 CMake 的开发环境中运行以下可选检查（`$jdk` 指向实际 JDK）：

```powershell
cmake -S native-bridge -B build/bootstrap-path-check -G "Visual Studio 17 2022" -A x64 "-DJAVA_HOME=$jdk" -DMOONS_BUILD_NATIVE_TESTS=ON
cmake --build build/bootstrap-path-check --config Release --target bootstrap-path-verification
& .\native-bridge\tests\verify-bootstrap-paths.ps1 -JdkHome $jdk
```

检查在独立 JVM 的 Live 阶段加载中文及空格目录里的测试 JAR，确认测试类由 bootstrap 类加载器定义，并验证缺失、空文件、损坏 JAR 和目录均被拒绝。可通过 `-RuntimeHomes @($jdk, $jbr)` 验证其他 JDK/JBR，运行库至少需要 Java 17。此检查不启动或注入游戏，也不随正式打包自动执行。

## 独立输出目录

需要保留原产物或避开文件占用时：

```powershell
.\gradlew.bat moonsExe '-Pmoons_build_directory=build/local' --project-cache-dir build/local/.gradle-project-cache
```

对应产物在 `build\local\dist`。多版本子构建自动隔离输出和项目缓存。构建出现错误时，先查看最早的异常；需要更多信息可加 `--stacktrace`。
