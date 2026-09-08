# Moons

Minecraft Java Edition 客户端项目，支持 **26.1.2 / 26.2**。通过 Windows 启动器和 JNI/JVMTI 桥接加载，使用共享客户端代码与独立版本适配。

## 构建环境

- Windows x64、x64 JDK 25。
- Visual Studio 2022 或 Build Tools 2022，安装“使用 C++ 的桌面开发”，包含 Windows SDK 和 CMake（3.20+）。
- .NET Framework 4.x C# 编译器。

仓库自带 Gradle 9.5.1 Wrapper，无需另行安装 Gradle。

## 构建启动器

在仓库根目录打开 PowerShell，将 `JAVA_HOME` 改为本机 JDK 25 的安装路径：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\gradlew.bat moonsExe
```

`moonsExe` 自动构建并打包两个受支持版本，无需分别指定版本。首次构建需要联网下载依赖；依赖缓存完整后可追加 `--offline`。

| 产物 | 路径 |
|---|---|
| Windows 启动器 | `build/dist/moons.exe` |
| UI 运行时依赖 | `build/dist/dependencies/moons-ui-runtime.jar` |

UI 运行时独立于 EXE，必须与同一次构建的启动器配套。启动对应版本的 Minecraft 后，本地运行可直接指定刚构建的依赖文件：

```powershell
$uiRuntime = (Resolve-Path '.\build\dist\dependencies\moons-ui-runtime.jar').Path
.\build\dist\moons.exe --ui-dependency-url $uiRuntime
```

## 仅构建 Java Payload

```powershell
# Minecraft 26.1.2
.\gradlew.bat moonsJar '-Pminecraft_version=26.1.2'

# Minecraft 26.2
.\gradlew.bat moonsJar '-Pminecraft_version=26.2'
```

对应产物为 `build/dist/agent/26_1/moons.jar` 和 `build/dist/agent/26_2/moons.jar`，由原生桥接加载，不能通过 `java -jar` 启动。

许可证见 [LICENSE.txt](LICENSE.txt)。
