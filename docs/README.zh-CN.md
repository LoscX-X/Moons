<h1 align="center">Moons</h1>

<p align="center">简体中文 · <a href="../readme.md">English</a></p>

Moons 是面向 Minecraft Java Edition 的客户端，提供功能模块、本地配置和 YSM 模型支持。

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

支持 **26.1.2**、**26.2**、**26.3** 和 **26.4-snapshot-1**。

## 版本支持

Moons 维护上面列出的正式版及 26.4 预览版。

快照、预发布版和候选发布版等开发版本，仅支持即将推出的最新 Minecraft 版本。

较早的预览构建和已停止支持的 Minecraft 版本可能仍可通过历史 Releases 或 CI 产物获取，但不再获得修复或兼容性更新。

## 风险与责任声明

- **自行核实授权。** 使用者应遵守适用法律、许可及相关服务规则；学习研究定位不能替代必要授权。
- **自行评估风险。** 本项目会改变游戏运行行为，可能影响运行、数据及相关服务。请备份重要数据，并在具有必要授权的环境中使用。
- **各自承担责任。** 公开源码本身不表示参与或担保第三方行为。除法律规定或另行有效约定外，作者不承担第三方独立行为的担保、代偿或赔偿义务。
- **按现状提供。** 在法律允许的最大范围内，不提供担保，不承担相关损失的赔偿或补偿义务，也不承诺特定使用或维护结果。
- **尊重第三方权利。** 外部内容及第三方组件适用各自许可；本项目的开放源码许可不自动覆盖这些内容。
- **保留法定边界。** 本声明不限制 GPL 已授予的权利，不排除依法不得排除的责任。具体范围见 [完整声明](DISCLAIMER.zh-CN.md) 和 [LICENSE](../LICENSE)。

## 如何使用

1. 从同一次 [Release](https://github.com/LoscX-X/Moons/releases) 下载 `moon-install-<版本>-<commit>.exe` 和 `moon-<版本>-<commit>.exe`。
2. 运行安装器安装或更新依赖。
3. 启动受支持版本的 Minecraft，再运行加载器，选择游戏进程并加载 Moons。
4. 按 **右 Shift** 打开 ClickGUI，这是默认按键。

依赖编号不变且已安装文件完好时，只需更新加载器；依赖有变化时再运行匹配版本的安装器。安装器内置 UI runtime 和 YSM 依赖，支持离线安装。开发构建可从 [Actions](https://github.com/LoscX-X/Moons/actions) 获取。

## 如何保存配置

1. 打开 **ClickGUI → Configs**。
2. 输入名称，点击 **Create**，再点击 **Save** 保存当前设置。
3. 之后选中已保存的配置，点击 **Load** 应用。

## 如何使用 YSM 模型

1. 将模型放入 YSM 页面显示的目录，默认位置为 `%APPDATA%\.moons\data\ysm\models`。
2. 打开 **ClickGUI → YSM**，点击 **Refresh**，选中模型后点击 **Apply**。
3. 点击 **Use vanilla** 可恢复原版玩家模型。

## 如何构建

准备 Windows x64、JDK 25，以及 [BUILDING.md](BUILDING.md) 列出的本地编译工具。在项目目录打开 PowerShell：

```powershell
.\gradlew.bat moonsPackages
```

产物为 `build/dist/moon-install.exe`、`build/dist/moon.exe` 和 `build/dist/dependencies/moons-ui-runtime.jar`。使用 IDE 时，以 JDK 25 导入 Gradle 项目并执行同名任务。

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
