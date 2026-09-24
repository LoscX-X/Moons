# 构建与更新

在仓库根目录运行 PowerShell。当前构建目标为 Minecraft **26.1.2、26.2、26.3、26.4-snapshot-1**；26.3 仅支持正式版，26.4 目前仅支持此快照。

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
| `moonsPackages` | 安装器、加载器和 UI 运行库 |
| `moonsUiRuntime` | `dependencies\moons-ui-runtime.jar` 及 SHA-256 文件 |
| `ysmAllVersions` | `moons-ysm-all.zip` 和四个单版本 ZIP |
| `ysmBundle -Pminecraft_version=26.2` | `moons-ysm-26.2.zip` |
| `moonsJar -Pminecraft_version=26.2` | `agent\26_2\moons.jar`，由加载器加载 |

## 客户端与依赖版本

- `gradle.properties` 的 `load_version` 是客户端版本，使用 `major.minor.patch`，可带预发布后缀。
- 依赖版本由 UI 和全部 YSM 文件的哈希生成，界面显示前 12 位。
- 两个 EXE 显示客户端/依赖版本，Windows 文件属性包含客户端版本，`--version` 输出详细元数据。
- 依赖版本记录位于 `MOONS_HOME/libraries/moons-dependencies.properties`。
- CI 以 `GITHUB_SHA` 标识构建，本地默认为 `local`，可用 `-Pmoons_build_id=<id>` 指定。
- CI 摘要和构建日志显示客户端版本、实际检出的 commit；打包后补充依赖、UI 和 YSM 编号。Release 标题包含版本和短 commit，说明及 `moons-build-info` 产物保留完整构建信息。

## CI 文件命名与按需更新

自动预发布版本保留最近 5 个，当前运行发布的版本、正式版本、草稿和不可变版本保留。Actions 构建附件保留 7 天。

CI 下载附件和 Release 中的 EXE 命名为 `moon-<load_version>-<8位commit>.exe`、`moon-install-<load_version>-<8位commit>.exe`。YSM 合集为 `moons-ysm-all.zip`。

依赖版本不变且已安装文件完好时，只需更新加载器。依赖内容变化或文件损坏时，运行匹配的安装器。

## YSM 库与热更新

`moon-install.exe` 安装匹配的文件；内容相同的文件跳过，更新失败时回滚。`MOONS_HOME` 默认是 `%APPDATA%\.moons`，也可通过同名环境变量指定。

```text
MOONS_HOME/
  libraries/
    moons-ysm-core.jar
    moons-ysm-codecs.jar
    moons-ysm-images.jar
  modules/
    moons-ysm-26.1.2.jar
    moons-ysm-26.2.jar
    moons-ysm-26.3.jar
    moons-ysm-26.4-snapshot-1.jar
```

将 `moons-ysm-all.zip` 解压至 `MOONS_HOME` 可安装 YSM 库和适配模块。运行 `build\dist\moon-install.exe --install-only` 可无界面安装全部依赖，退出码 0 表示成功。使用 `moon.exe --verify-dependencies` 检查依赖是否完整。

修改 YSM 库或模块后，可通过模块重载更新。修改 bootstrap API、宿主渲染钩子或注入位置后，需要重新构建 EXE、重启游戏并重新注入。

查看构建产物路径和时间，并运行加载器：

```powershell
Get-Item .\build\dist\moon.exe | Select-Object FullName, LastWriteTime, Length
& .\build\dist\moon.exe
```

## 缓存限制

加载器启动、安装器成功完成更新时会尝试清理可再生成的缓存。存在 `java` / `javaw` 进程时延后到之后运行，避免删除延迟加载仍需使用的 JAR；文件被占用、访问受限或路径含目录链接时也会跳过。

| 类别 | 保留上限 | 体积上限 | 过期时间 |
| --- | --- | --- | --- |
| UI runtime | 当前版本及一个旧版本 | 256 MiB | 旧版本 30 天 |
| 载荷 / DLL / API 缓存 | 12 份 | 128 MiB | 30 天 |
| 模块和库副本 | 32 份 | 256 MiB | 30 天 |
| 宿主 runtime 副本 | 8 份 | 64 MiB | 30 天 |
| 失败安装留下的 YSM 备份 | 2 份 | 64 MiB | 7 天 |
| 旧 `%TEMP%/moons` runtime 缓存 | 8 份 | 64 MiB | 7 天 |

YSM 临时备份在更新成功后删除。缓存清理保留当前 UI 版本和写入不足 10 分钟的文件，超量时优先移除较旧条目；受保护或被占用的文件可能使缓存暂时超过上限。

Runtime 缓存位于 `MOONS_HOME/cache/runtime`。配置、模型、预设和 `MOONS_HOME/modules` 中的正式模块不属于缓存清理范围。

## 按需验证

按修改范围选择验证任务：

```powershell
# 仅编译全部版本的正式源码
.\gradlew.bat compileAllVersions

# 全部版本：编译、实际游戏注入点、YSM 行为、材质和更新回滚验证
.\gradlew.bat checkAllVersions

# 局部修改只跑相关检查
.\gradlew.bat verifyYsmCore verifyYsmRenderSetup '-Pminecraft_version=26.3'
.\gradlew.bat check '-Pminecraft_version=26.4-snapshot-1'
.\gradlew.bat verifyYsmCore '-Pysm_test_model=C:\Models\example.ysm'
.\gradlew.bat verifyYsmPackage
.\gradlew.bat verifyLauncherPackages # 隔离目录验证两个真实 EXE，不注入游戏
.\gradlew.bat benchmarkYsm
```

`verifyYsmCore` 使用所选 Minecraft 的 Java 依赖运行一次核心验证，包括头部追踪、第一人称过滤、动画和队列快照。`verifyYsmRenderSetup` 检查该版本的材质与顶点变换；`benchmarkYsm` 测 CPU 时间和分配量，不代表游戏 FPS。最终视觉效果仍需进游戏确认第三人称抬头/低头、第一人称双臂、持物、透明材质和动作切换。

CI 在 push、PR 和默认的手动运行中先执行 `verifyMinecraftTransformers checkAllVersions`，通过后再打包。手动运行时可取消勾选 `verify` 来跳过验证。使用 `formatCode` 格式化代码。

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
