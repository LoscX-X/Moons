# 构建与使用：Minecraft 1.8.9

本分支 `legacy/1.8.9` 使用原版 1.8.9 JAR 与校验过的 MCP stable_22 映射，重新适配完整功能框架。默认构建目标为 **1.8.9**；现代版本请使用主分支。

**1.8.9 使用者需自行构建本分支，配套使用同次生成的安装器与加载器。** 主分支 Release 中的现代版本包不包含此适配。

## 环境

- Windows x64，**JDK 25**。编译和运行游戏都使用 Java 25；原版启动器的 Java 8 不能加载本客户端。
- Visual Studio / Build Tools 的 C++ 桌面开发组件、Windows SDK、CMake，以及 Windows 自带 .NET Framework 4.x C# 编译器。
- 首次构建需要联网取得 Gradle、MCP 和依赖。游戏 JAR 默认从 `%APPDATA%/.minecraft/versions/1.8.9/1.8.9.jar` 读取；缺少时取得官方原版。所有游戏和映射输入均校验摘要，游戏代码不打入产物。

## 构建

在本分支工作目录运行：

```powershell
.\gradlew.bat moonsPackages
```

| 任务 | 默认产物 |
| --- | --- |
| `moonsPackages` | `build/dist/Moons-install.exe`、`build/dist/Moons.exe`、使用说明 |
| `moonsJar` | `build/dist/agent/1_8/moons.jar`，原生桥接加载的载荷 |
| `moonsUiRuntime` | `build/dist/dependencies/moons-ui-runtime.jar` 及摘要 |
| `ysmBundle` | `build/dist/moons-ysm-1.8.9.zip` |

`clientModuleJar` 和 `ysmJar` 自动将编译后的游戏引用重映射为原版混淆名称。`build/named/` 中的开发 JAR 仅用于检查，不能代替发布载荷。

可指定输入与独立输出目录：

```powershell
.\gradlew.bat moonsPackages '-Pminecraft_189_jar=C:/Games/.minecraft/versions/1.8.9/1.8.9.jar' '-Pmoons_build_directory=build/1_8'
```

上述示例的 EXE 位于 `build/1_8/dist/`。缓存完整后可加 `--offline`。

`gradle.properties` 的 `load_version` 使用 `X.Y.Z.x-Experiment` 或 `X.Y.Z.x-Release`，第四段是从 0 开始的补丁顺序；每次 push 前加 1，前三段变化则归 0。同一次推送重跑构建不再次加号，Windows 文件版本使用这四段数字。旧冻结制品的版本名保持不变。`Experiment` 是预发布且不覆盖 Latest，`Release` 才是正式版；本分支的 push 仅触发检查，1.8.9 使用者仍需自行构建。

## 使用

1. 运行同次构建的 `Moons-install.exe` 安装 UI、共享 YSM 库和 1.8.9 YSM 模块。
2. 在启动器中为原版 **1.8.9** 选择 **Java 25 x64**，启动游戏。
3. 运行 `Moons.exe`，选择该游戏进程加载。
4. 按默认键 **右 Shift** 打开完整 ClickGUI。

加载器目前校验原版 1.8.9 的实际类字节码；Forge、OptiFine、Lunar 等改写核心类的环境尚未纳入这一分支的验证范围。

默认数据目录为 `%APPDATA%/.moons`，可通过 `MOONS_HOME` 指定。建议 1.8.9 与现代版本使用各自的数据目录。YSM 模型放在界面显示的模型目录，默认是 `data/ysm/models`；支持模型选择、材质、动画、编辑及恢复原版模型。

SilentAura 仅有 Legacy 战斗路径，保留 47 项适用设置。原版没有的攻击蓄力、副手、盾牌、鞘翅、重锤、长矛和图腾相关选项不出现；不提供 AutoMace、AutoSpear、AutoTotem 三个无对应机制的模块。其余 64 个模块入口及适用的界面、配置和 YSM 功能保留。

## 验证

```powershell
.\gradlew.bat check
.\gradlew.bat verifyMinecraft189 verifyMinecraft189Catalog verifyMinecraft189WorldInventory
.\gradlew.bat verifyYsmCore verifyYsmRenderSetup verifyYsmQueries
.\gradlew.bat verifyLauncherPackages
```

`verifyMinecraft189` 用真实原版与命名游戏字节码核对注入位置、控制流、桥接签名、重复转换和正反映射。`verifyMinecraft189Catalog` 核对模块注册、设置可读性、SilentAura 显隐和持久化。`verifyLauncherPackages` 在隔离目录验证真实安装器和加载器，不注入用户游戏。

完整载荷的混淆链接检查：

```powershell
.\gradlew.bat clientModuleNamedJar ysmNamedJar verifyMinecraft189 '-Pminecraft189_verify_payloads=build/named/moons-payload/1_8/moons-core-features.jar;build/named/modules/1_8/moons-ysm-1.8.9.jar'
```

`checkAllVersions` 和 `compileAllVersions` 在本分支只处理 1.8.9。算法、模块时序和游戏内验证记录保存在本地 `dev-docs/`。
