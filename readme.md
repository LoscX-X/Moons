# Moons

Minecraft Java Edition 客户端项目，支持 **26.1.2 / 26.2**。通过 Windows 启动器和 JNI/JVMTI 桥接加载，使用共享客户端代码与独立版本适配。

## Actions 打包

打开 **Actions → Project checks and packages → Run workflow**，选择打包内容：

| 选项 | 内容 |
|---|---|
| `all` | 完整版、轻量版和 UI 依赖，默认选项 |
| `full` | `moons-full.exe`，内置 UI 依赖，可直接运行 |
| `download` | `moons.exe`，首次运行自动下载并缓存匹配的 UI 依赖 |
| `ui-runtime` | 仅生成 `moons-ui-runtime.jar` 和 SHA-256 文件 |

主分支推送会在检查通过后自动打包全部产物；Pull Request 仅运行检查。手动选择 `ui-runtime` 时只构建依赖，不运行 Minecraft 检查。

产物可在该次运行的 **Artifacts** 和对应的 **GitHub Releases 预发布版本**中下载。每次发布使用独立地址，轻量版始终下载同一次构建的 UI JAR。完整版只是不需要额外下载 UI 依赖，项目自身的联网功能仍按原设置运行。

## 构建环境

- Windows x64、x64 JDK 25。
- Visual Studio 2022 或 Build Tools 2022，安装“使用 C++ 的桌面开发”，包含 Windows SDK 和 CMake（3.20+）。
- .NET Framework 4.x C# 编译器。

仓库自带 Gradle 9.5.1 Wrapper，无需另行安装 Gradle。

## 本地构建

在仓库根目录打开 PowerShell，将 `JAVA_HOME` 改为本机 JDK 25 的安装路径：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\gradlew.bat moonsPackages
```

| 任务 | 产物 |
|---|---|
| `moonsPackages` | 下列全部产物，一次构建两个 Minecraft 版本 |
| `moonsFullExe` | `build/dist/moons-full.exe` |
| `moonsExe` | `build/dist/moons.exe` |
| `moonsUiRuntime` | `build/dist/dependencies/moons-ui-runtime.jar` 及 `.sha256` |

首次构建需要联网下载依赖；依赖缓存完整后可追加 `--offline`。本地构建轻量版时，可通过 `-Pmoons_ui_download_url` 指定匹配的 JAR 下载地址，或运行时直接使用本次构建的文件：

```powershell
$uiRuntime = (Resolve-Path '.\build\dist\dependencies\moons-ui-runtime.jar').Path
.\build\dist\moons.exe --ui-dependency-url $uiRuntime
```

启动对应版本的 Minecraft 后，再运行启动器。

## 仅构建 Java Payload

```powershell
.\gradlew.bat moonsJar '-Pminecraft_version=26.1.2'
.\gradlew.bat moonsJar '-Pminecraft_version=26.2'
```

对应产物为 `build/dist/agent/26_1/moons.jar` 和 `build/dist/agent/26_2/moons.jar`，由原生桥接加载，不能通过 `java -jar` 启动。

许可证见 [LICENSE.txt](LICENSE.txt)。

---

安全协议及免责声明与使用须知

一、项目性质

本项目是一个以 Minecraft Java Edition 为运行载体的开源研究与测试项目，主要用于学习、研究和验证运行时注入、事件拦截、模块加载及相关软件工程技术。

本项目不属于 Minecraft 官方产品，不受 Mojang Studios 或 Microsoft 的认可、赞助或授权，也不代表上述主体的立场。

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

二、使用范围

本项目仅供合法的软件研究、个人学习、兼容性测试及经明确授权的安全测试使用。

使用者不得将本项目用于任何违反适用法律法规、平台规则、软件许可协议或他人合法权益的活动，包括但不限于：

未经授权访问、修改、干扰或破坏第三方设备、账户、服务器及数据；

窃取、收集、上传、出售或泄露个人信息、身份凭据及其他敏感数据；

绕过访问控制、安全验证、付费机制或平台限制；

实施欺诈、攻击、勒索、恶意控制、持久化驻留或其他违法行为；

盗取账号或令牌、远程控制、数据外传、持久化隐藏、绕过安全软件；

将本项目伪装成 Minecraft、Mojang Studios 或 Microsoft 的官方产品。

三、数据与隐私

本项目的设计目的不包括收集、窃取、监控、上传或向外部主体提供用户及第三方的个人信息、账户凭据、聊天记录、设备信息或其他非公开数据。

除非项目文档另有明确说明，官方发布版本不应主动连接与项目功能无关的外部服务。

使用者应自行审查所使用的源代码、构建产物、配置文件及网络行为。对于第三方修改版本、非官方构建、重新分发版本、外部插件或使用者自行添加的代码，项目维护者无法保证其安全性、完整性及数据处理行为。

请仅从本项目明确列出的官方仓库或发布渠道获取源代码和构建产物。

四、使用者责任

下载、编译、安装、运行、修改或分发本项目，即表示使用者理解并同意：

使用者应确保其行为已经获得必要授权，并符合所在地适用法律、Minecraft 相关协议、服务器规则及其他适用条款；

使用者应自行评估运行时注入及修改游戏行为可能造成的安全性、稳定性和兼容性风险；

使用者应自行备份重要文件，并优先在隔离的测试环境、个人存档或获得明确授权的环境中使用；

因使用者违反法律、协议、服务器规则或本声明而产生的责任，由使用者自行承担；

项目名称、开源属性或研究目的，不构成对任何具体使用行为合法性、合规性或安全性的保证。

五、风险提示

运行时注入、字节码修改、Hook、Mixin 或类似机制可能与其他 Mod、加载器、游戏版本、安全软件及系统环境发生冲突，并可能导致：

游戏崩溃、存档损坏或配置丢失；

性能下降、功能异常或版本不兼容；

服务器拒绝连接、账户限制或其他平台处置；

安全软件产生警告、拦截或误报；

因错误配置、第三方修改或不当操作造成的其他损失。

使用者应在充分了解相关风险后自行决定是否运行本项目。

六、无担保声明

在适用法律允许的最大范围内，本项目按照“现状”和“可用状态”提供，不附带任何明示或默示担保，包括但不限于对适销性、特定用途适用性、准确性、可靠性、兼容性、安全性及不侵权的担保。

项目作者及维护者不保证本项目：

始终可用、无错误或不中断；

适配所有 Minecraft、Java、Mod Loader 或操作系统版本；

不会触发安全软件、平台风控或服务器检测；

能够满足任何特定目的或产生任何特定结果。

在适用法律允许的最大范围内，项目作者、维护者及贡献者不对因使用或无法使用本项目而产生的直接、间接、附带、特殊、惩罚性或后果性损失承担责任。

如适用法律不允许排除或限制部分责任，则相关责任按照该法律允许的最低范围承担。

七、知识产权

Minecraft、Mojang、Mojang Studios、Microsoft 及其相关名称、商标、图形和游戏资源，归其各自权利人所有。

本项目仅对项目作者及贡献者原创的代码和内容依照仓库所附开源许可证进行授权。该授权不包括 Minecraft 本体、修改后的游戏客户端或服务端、官方资源，以及任何项目无权再授权的第三方内容。

使用者在复制、修改或分发本项目时，应同时遵守：

本项目采用的开源许可证；

Minecraft 最终用户许可协议及使用指南；

所使用依赖项和第三方组件的许可证；

所在地区适用的法律法规。

本声明不替代或修改仓库中开源许可证所规定的权利与义务；如两者存在冲突，应以适用的许可证及强制性法律规定为准。

八、第三方内容与非官方版本

项目维护者不对第三方网站、镜像、整合包、衍生项目、修改版本或非官方构建提供担保，也不对其安全性、合法性、完整性及可用性负责。

任何第三方对本项目的修改、重新分发或组合使用，均不当然代表项目作者及维护者的认可、授权或合作关系。

相关源代码
https://github.com/LoscX-X/Moons
