<h1 align="center">Moons</h1>

<p align="center">简体中文 · <a href="../readme.md">English</a></p>

Moons 是面向 Minecraft Java Edition 的 Windows 客户端，提供功能模块、本地配置和 YSM 模型支持。

支持 **26.1.2**、**26.2**、**26.3**。

## 版本支持

Moons 持续维护最近四个 Minecraft 稳定版本系列。

快照、预发布版和候选发布版等开发版本，仅支持即将推出的最新 Minecraft 版本。

较早的预览构建和已停止支持的 Minecraft 版本可能仍可通过历史 Releases 或 CI 产物获取，但不再获得修复或兼容性更新。

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
