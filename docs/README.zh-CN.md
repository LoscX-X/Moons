> [!IMPORTANT]
> **Minecraft 1.8.9 请使用者自行构建。** 检出 `legacy/1.8.9` 分支，在 Windows x64 上使用 JDK 25 执行 `.\gradlew.bat moonsPackages`，随后配套使用生成的 `build/dist/Moons-install.exe` 和 `build/dist/Moons.exe`。完整步骤见 [构建说明](BUILDING.md)。主分支 Release 中的安装器和加载器面向现代版本。

<h1 align="center">Moons</h1>

<p align="center">简体中文 · <a href="../readme.md">English</a></p>

Moons 是面向 Minecraft Java Edition 的客户端，提供功能模块、本地配置和 YSM 模型支持。[官网](https://moons.cendreal.com/)。

`legacy/1.8.9` 分支基于完整框架适配原版 1.8.9，保留 64 个适用模块、完整设置界面、配置和 YSM。SilentAura 仅保留 Legacy 及 47 项适用设置；不提供原版没有对应机制的 AutoMace、AutoSpear、AutoTotem。

本分支仅保存开发快照，等待用户实测，尚未合入 `latest` 或发布正式版；已有检查仅覆盖部分功能。

> [!WARNING]
> **本项目发布目的仅为学习、研究和技术交流。**
>
> **本项目不会以任何方式获取盈利。** 维护者承诺不通过本项目开展直接或间接盈利活动，官方发布的源码、构建产物及功能免费提供。
>
> **本项目以 Mojang 发布的 Minecraft: Java Edition 本体为游戏修改对象，所有相关修改均须遵守 [Minecraft EULA](https://www.minecraft.net/en-us/eula) 及适用的官方条款，修改范围不扩展至第三方独立软件、服务或资源。** 第三方依赖和外部内容仍适用各自许可；此说明不构成官方认可或全面合规保证。
>
> **作者无法逐一审查、实时监控或控制他人的实际行为。** 行为人应依法承担自身行为的责任。
>
> **本项目按现状提供，使用风险由使用者在法律允许的范围内自行承担。** 使用前请阅读 [完整免责声明](DISCLAIMER.zh-CN.md)。依法不得排除的责任及法定权利不受影响。

支持 **Java 25 x64 下运行的原版 Minecraft 1.8.9**。现代版本请使用主分支；改写核心类的第三方客户端尚未纳入本分支验证。

## 风险与责任声明

- **自行核实授权。** 使用者应遵守适用法律、许可及相关服务规则；学习研究定位不能替代必要授权。
- **自行评估风险。** 本项目会改变游戏运行行为，可能影响运行、数据及相关服务。请备份重要数据，并在具有必要授权的环境中使用。
- **各自承担责任。** 公开源码本身不表示参与或担保第三方行为。除法律规定或另行有效约定外，作者不承担第三方独立行为的担保、代偿或赔偿义务。
- **按现状提供。** 在法律允许的最大范围内，不提供担保，不承担相关损失的赔偿或补偿义务，也不承诺特定使用或维护结果。
- **尊重第三方权利。** 外部内容及第三方组件适用各自许可；本项目的开放源码许可不自动覆盖这些内容。
- **保留法定边界。** 本声明不限制 GPL 已授予的权利，不排除依法不得排除的责任。具体范围见 [完整声明](DISCLAIMER.zh-CN.md) 和 [LICENSE](../LICENSE)。

## 如何使用

1. 按 [构建说明](BUILDING.md) 自行构建 `legacy/1.8.9` 分支，配套使用同次构建的 `build/dist/Moons-install.exe` 和 `build/dist/Moons.exe`。
2. 运行安装器安装或更新依赖。
3. 使用 Java 25 x64 启动原版 Minecraft 1.8.9，再运行加载器，选择游戏进程并加载 Moons。
4. 按 **右 Shift** 打开 ClickGUI，这是默认按键。

依赖编号不变且已安装文件完好时，只需更新加载器；依赖有变化时再运行匹配版本的安装器。自行构建的安装器内置 UI runtime 和 YSM 依赖，支持离线安装。

## 如何保存配置

1. 打开 **ClickGUI → Configs**。
2. 修改自动保存到当前加载的 JSON 配置；输入名称并点击 **Create**，复制当前完整设置。
3. 选中其他配置，点击 **Load** 才切换；**Overwrite** 只覆盖选中的目标文件，不切换当前配置。

启动读取普通的 `config/profiles/default.json`。旧 `moons.properties` 的全部设置会在首次运行时迁入其中，已有命名 JSON 配置继续可用。

## 如何使用 YSM 模型

1. 将模型放入 YSM 页面显示的目录，默认位置为 `%APPDATA%\.moons\data\ysm\models`。
2. 打开 **ClickGUI → YSM**，点击 **Refresh**，选中模型后点击 **Apply**。
3. 点击 **Use vanilla** 可恢复原版玩家模型。

## 如何构建

准备 Windows x64、JDK 25，以及 [BUILDING.md](BUILDING.md) 列出的本地编译工具。在项目目录打开 PowerShell：

```powershell
.\gradlew.bat moonsPackages
```

产物为 `build/dist/Moons-install.exe`、`build/dist/Moons.exe` 和 `build/dist/dependencies/moons-ui-runtime.jar`。使用 IDE 时，以 JDK 25 导入 Gradle 项目并执行同名任务。

## 如何参与开发

- 在 [Issues](https://github.com/LoscX-X/Moons/issues) 中附上 Minecraft 版本、复现步骤和相关日志。
- 修改代码后，完成受影响功能的编译和相关检查，再提交 PR。验证方式见 [构建文档](BUILDING.md)。

## 项目活跃度

![Moons 仓库活跃度](https://repobeats.axiom.co/api/embed/fbaacbea8a2feb78305a937b23e2981cf6bf564a.svg "Repobeats analytics image")

## Inspired Projects

- [CCBlueX/LiquidBounce](https://github.com/CCBlueX/LiquidBounce)
- [sdf123098/Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher)
- [IamNespola/OpenMyau-Plus](https://github.com/IamNespola/OpenMyau-Plus)

## 许可证

[GPL-3.0](../LICENSE)。第三方代码保留原有许可证和声明。

完整的安全协议、免责声明与使用须知见 [免责声明](DISCLAIMER.zh-CN.md)。

非 Minecraft 官方产品，与 Mojang 或 Microsoft 无关联。
