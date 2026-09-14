# 构建与更新

在仓库根目录运行 PowerShell。当前构建目标为 Minecraft **26.1.2、26.2、26.3-rc-3**；26.3 载荷同时兼容 **26.3-rc-2**。

## 环境

- Windows x64，JDK 25 或更高版本；`JAVA_HOME` 指向 JDK。
- Visual Studio / Build Tools 的 C++ 桌面开发组件、Windows SDK 和 CMake 工具，用于 JNI 桥接 DLL。
- .NET Framework 4.x 的 `csc.exe`，用于启动器。构建脚本自动查找 Windows 自带的位置。
- 首次构建需要联网下载 Gradle 和依赖；缓存完整后才使用 `--offline`。

## 日常构建

```powershell
Set-Location E:\McEnv\moons
.\gradlew.bat moonsExe
```

产物为 `build\dist\moons.exe`。这个命令会构建三个版本的宿主、共享 YSM 库和三个适配模块，并把 YSM 包内置到 EXE；无需再单独执行 YSM 打包命令。

`moons.exe` 使用缓存的 UI 运行库。向其他电脑分发时，用 `-Pmoons_ui_download_url=https://.../moons-ui-runtime.jar` 指定同次构建的 UI 包下载地址；没有缓存或下载地址时，选择包含 UI 依赖的完整版：

```powershell
.\gradlew.bat moonsFullExe
```

| 命令 | `build\dist` 下的产物 |
| --- | --- |
| `moonsExe` | `moons.exe`，下载/缓存 UI 依赖 |
| `moonsFullExe` | `moons-full.exe`，内置 UI 依赖 |
| `moonsPackages` | 两种 EXE 和 UI 运行库 |
| `moonsUiRuntime` | `dependencies\moons-ui-runtime.jar` 及 SHA-256 文件 |
| `ysmAllVersions` | `moons-ysm-all.zip` 和三个单版本 ZIP |
| `ysmBundle -Pminecraft_version=26.2` | `moons-ysm-26.2.zip` |
| `moonsJar -Pminecraft_version=26.2` | `agent\26_2\moons.jar`，由启动器加载 |

指定单版本只影响对应任务；EXE 始终包含全部受支持版本。

## YSM 库与热更新

两个 EXE 启动时均自动安装匹配的文件；内容相同的文件跳过，更新失败时回滚。`MOONS_HOME` 默认是 `%APPDATA%\.moons`，也可通过同名环境变量指定。

```text
MOONS_HOME/
  libraries/
    moons-ysm-core.jar
    moons-ysm-codecs.jar
    moons-ysm-images.jar
  modules/
    moons-ysm-26.1.2.jar
    moons-ysm-26.2.jar
    moons-ysm-26.3-rc-3.jar
```

三个 Minecraft 版本共用上面的三份库，仅适配模块不同。无需为每个模型复制 libs。也可把 `moons-ysm-all.zip` 解压至 `MOONS_HOME`，或运行 `build\dist\moons.exe --install-ysm-only` 只安装 YSM。

仅修改 YSM 库/模块时，可通过模块重载更新；修改 bootstrap API、宿主渲染钩子或注入位置后，须退出旧游戏并用新 EXE 注入新启动的游戏。`ysmAllVersions` 不生成 EXE，无法升级进程中的旧宿主。

避免从另一份仓库或旧目录启动，构建完成后确认绝对路径和时间：

```powershell
Get-Item .\build\dist\moons.exe | Select-Object FullName, LastWriteTime, Length
& .\build\dist\moons.exe
```

## 按需验证

普通打包不强制执行自定义测试。重复的核心检查、源码/EXE 字符串扫描以及未使用的格式检查分支已移除。保留编译失败、包文件缺失、跨版本共享库不一致等会直接影响产物正确性的检查。

```powershell
# 仅编译全部版本的正式源码
.\gradlew.bat compileAllVersions

# 全部版本：编译、实际游戏注入点、YSM 行为、材质和更新回滚验证
.\gradlew.bat checkAllVersions

# 局部修改只跑相关检查
.\gradlew.bat verifyYsmCore verifyYsmRenderSetup '-Pminecraft_version=26.3-rc-3'
.\gradlew.bat verifyYsmCore '-Pysm_test_model=C:\Models\example.ysm'
.\gradlew.bat verifyYsmPackage
.\gradlew.bat benchmarkYsm
```

`verifyYsmCore` 使用所选 Minecraft 的 Java 依赖运行一次核心验证，包括头部追踪、第一人称过滤、动画和队列快照。`verifyYsmRenderSetup` 检查该版本的材质与顶点变换；`benchmarkYsm` 测 CPU 时间和分配量，不代表游戏 FPS。最终视觉效果仍需进游戏确认第三人称抬头/低头、第一人称双臂、持物、透明材质和动作切换。

CI 在 push、PR 和默认的手动运行中先执行 `verifyMinecraftTransformers checkAllVersions`，通过后再打包。`checkAllVersions` 包含各受支持版本的编译、Transformer 和 YSM 验证；手动运行时可取消默认勾选的 `verify` 来跳过验证。格式化按需运行 `formatCode`，不挂在打包路径上。

## Windows bootstrap 中文路径验证

桥接配置使用 UTF-8；调用 Windows JVMTI 文件接口时，桥接按目标进程的系统代码页无损转换路径，并保留短路径和标准编码兼容处理。无需移动中文目录。日志会记录实际 bootstrap JAR 路径及采用的编码。系统代码页无法表示的生僻字符依赖可用的短文件名或 JVM 的 Unicode 路径支持，不会用 `?` 替换后误加载其他文件。

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
