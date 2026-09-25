# Moons 本地 YSM

YSM 通过 Moons 的 JNI/JVMTI 宿主热加载，核心不依赖 Fabric、Mixin、Architectury 或服务端。复用原项目的资源、动画、Molang 和物理职责划分；游戏状态、渲染提交和音频输出放在版本适配器。只影响本机，不发送 YSM 同步数据包，不接入其他模组的兼容层。

## 使用与升级

1. 首次使用此版本须退出旧游戏进程，以新构建的 `moons.exe` 注入新启动的游戏。旧宿主缺少本次 bootstrap API 和注入点，不能仅靠替换 YSM jar 升级。
2. 匹配的安装器会安装三个共享库和 `26.1.2`、`26.2`、`26.3`、`26.4-snapshot-1` 适配模块；文件摘要一致时不重复写入。升级失败会恢复已替换的文件；旧文件备份保留在 `MOONS_HOME/cache/ysm-install/`。独立分发时可将 `moons-ysm-all.zip` 一次解压到 `MOONS_HOME`。
3. 模型放入 `MOONS_HOME/data/ysm/models/`，支持 crypto3 `.ysm`、模型 zip 和解压目录。默认 Windows 路径为 `%APPDATA%/.moons/data/ysm/models/`。
4. 打开 Moons 设置的 YSM 页面，Refresh 后选择模型并 Apply。Use vanilla 恢复原版。模型失败时保留此前可用实例，错误显示在页面中。

选择保存到 `data/ysm/ysm.properties`；每个模型的参数、纹理选择和姿态保存为独立 JSON。修改参数无需重载整个模型。文件或外部库更新会触发重载，失败回滚保留当前模块。仅在新宿主安装后使用这一热更新机制。

## 管理与公共库

| 目录 | 职责 |
| --- | --- |
| `src/libraries/ysm` | 解密/读取、模型装配、几何/定位器、控制器、Molang、物理、参数与姿态、音频生命周期 |
| `src/libraries/ysm-codecs` | Java Ogg/Opus 解码，声道混合、pre-skip、输出增益、结尾填充裁剪 |
| `src/libraries/ysm-images` | WebP 解码与 AVIF 工具封装；Windows x64 AVIF 工具随库携带 |
| `src/minecraft/versions/{26_1,26_2,26_3,26_4}/ysm/adapter` | 对应版本的状态采样、玩家/手臂/附属实体、纹理与材质、装备图层、原版音频通道和粒子 |
| `src/minecraft/shared/ysm/adapter` | 各版本共用的完整顶点提交与法线变换 |
| `src/bootstrap/api` 的 `YsmSelector` / `YsmStudio` | 不含游戏类型的选择、编辑与调试接口 |
| `src/minecraft/shared/.../ui/clickgui` 的 YSM 页面 | 通用 Moons UI，通过快照读取状态和提交编辑 |

三个 lib jar 在磁盘上各保留一份，供多个版本适配器共用。模块各自使用可关闭 classloader 和带摘要的缓存副本，避免 Windows 文件锁妨碍更新。游戏版本适配器必须严格匹配，宿主根据模块描述中的版本选择适配器。多个版本的模块可以同时放入 `modules`；共享库 API 改动时也需要升级，由启动器自动安装配套文件。不同版本的包若包含不同的共享库，合并构建会直接失败。

`./gradlew.bat moonsExe` 是启动器构建命令，`moonsInstallExe` 构建携带完整 YSM 包的安装器（`moon-install.exe` 负责安装三个共享库与各版本适配模块）。`ysmAllVersions` 只构建和打包外部 YSM 库与模块，不生成 EXE，不能用它给旧宿主增加渲染注入点。旧 EXE 缺少注入点时，模型可能显示 Active，但人物外观不变；必须用新 EXE 注入新游戏进程。

26.2 使用绑定组渲染管线和新的相机、名字标签接口。26.3 使用 RenderPearl 的原生实体材质及 OIT 透明管线，并适配挥手状态、骨骼旋转、装备提交和 SDL 输入。26.4-snapshot-1 沿用这套适配，并更新渲染管线类型及装备图层返回值。模型中的 `ysm.keyboard`/`ysm.mouse` 继续接受原来的 GLFW 编号；不受 SDL 支持的键返回 false。

## 加载与帧开销

配置及模型目录指纹扫描、模型/双视角动画会话创建、偏好读取、纹理解码和透明度准备在后台执行。身体与手臂共享一次解码结果；准备完成后在游戏线程提交纹理并替换实例。切换选择、禁用或卸载会取消过期任务，迟到的会话被关闭，加载失败时保留当前实例。

加载或切换纹理时，将可见面按骨骼、材质整理成连续数组。各版本的动态纹理均使用最近邻采样，因此直接根据面内纹理透明度分类，无需把相邻透明像素的面一并送入混合流程。完全不透明的普通面和发光面分别使用不混合材质；发光面保留原有发光着色器。含透明像素的面继续遵循原有混合或裁剪规则，减少透明排序和 OIT 的几何量。原有模型细节、透明度和剔除规则保留。

每帧先计算骨骼姿态与各材质顶点数，再直接填充交给渲染队列的数组，省去临时构建缓冲区的整份复制。骨骼矩阵不变时复用上一帧变换结果；隐藏、手臂过滤及换纹理会正确更新缓存。快照保持独立，后续帧不会改写排队中的顶点或定位器。只有工具调用兼容接口 `Mesh.vertices()` 时才额外合并并缓存。游戏提交使用完整顶点接口，每个四边形只变换一次法线。

身体在动画姿态之后叠加头部俯仰与相对身体的偏航，头部附件和子骨骼随动，重复绘制不累积角度。Molang 和自动头部追踪统一使用上游模型坐标方向；相对偏航先归一化到 `[-180°, 180°)`，再限制到 `±85°`，使作者编写的头部补偿和身体随动能够正确配合。该限制作用于视角追踪输入，保留模型自身的动画旋转。第一人称使用视图空间手臂模型，不重复叠加身体的头部追踪；只更新不可见身体的动画与绝对骨骼位置，再生成指定手臂的网格，保留透明、发光和剔除材质。工作室快照仅在页面读取期间生成，限制为每秒最多十次。

`benchmarkYsm` 使用固定的 64 骨骼、4096 顶点模型，预热后测量 1000 帧的 CPU 网格耗时与线程分配量，不设依赖机器速度的通过阈值。它不等同于游戏内 FPS 测试。

多版本子构建同时隔离输出目录与 Gradle 项目缓存，避免子构建把父构建或其他版本的编译结果当作过期文件清理；检查、打包和基准可以串联执行。

核心仍使用游戏提供的普通 Java 库 Gson、JOML、Netty buffer、fastutil 和 Commons Lang。Netty 只用于二进制缓冲。跨版本需验证这些依赖，以及玩家状态与游戏生命周期的变化，不能仅检查 GPU 提交函数。

## 本地功能

- 动画装配与控制器：主动作、并行动作、装备、持物/挥动/使用、载具/乘客、额外动作、第一人称手臂；保留初始化、更新、时间线与 defer 事件。
- 同名动画按上游装配顺序采用后定义，身体、派生/作者提供的第一人称动画和类型元数据保持一致。物品短名称、资源 ID/标签和药水箭效果查询沿用各自的上游约定。
- 附属实体采样自身的世界、运动、装备、武器、生物和船桨状态；头部方向使用插值后的头身相对角，地图角度与步行余弦使用模型上下文和播放时间。
- 原版 Molang 执行器、插值和混合、控制器过渡、变量作用域、物理弹簧与控制函数；未知查询/表达式错误进入调试信息。
- 第三人称与第一人称骨骼、透明/发光/剔除材质，纹理切换、定位器、持物、头部物品、鞘翅和肩上鹦鹉；附属投射物与本地坐骑有独立运行时。
- 本地模型声音、原版声音和原版粒子；Opus/Vorbis 通过 Minecraft 音频系统播放，跟随设备、音量与暂停状态。切换模型/卸载时停止自有声音并释放纹理和运行时。
- 纹理支持原始 RGBA（格式 -1）、PNG/JPEG/BMP、WebP 和 AVIF。透明通道按图像内容保留。

## 独立页面

| 页面 | 操作 |
| --- | --- |
| YSM | 模型搜索、选择、应用、刷新、恢复原版 |
| Parameters | 模型作者提供的身体/服装等开关、数值、单选参数；纹理选择与本地保存 |
| Actions | 额外动作和完整动画列表，含第一人称动作；播放、暂停、速度与时间定位 |
| Pose | 单骨骼位移、旋转、缩放、显示状态和重置 |
| Debug | Molang 表达式、变量、控制器状态与运行错误 |

## 验证与实机验收

`verifyYsmCore` 使用所选 Minecraft 版本的实际 Java 依赖检查 crypto3/目录读取、层级几何、动画事件与控制器、变量作用域、参数保存、头部追踪、物理、音频生命周期、WebP/AVIF 透明度，以及队列快照稳定性、双臂过滤、后台会话移交、保存的透明纹理和只更新姿态时的播放/跳转。可选 `-Pysm_test_model=<目录或文件>` 对实际模型只读验证，不打包模型资源；采样身体、第一人称、投射物及载具的全部动画，并在起始、中间和结束时刻检查有限坐标。任何已执行表达式的解析或求值错误都会使验证失败，包括超出调试列表 100 条上限的错误；模型资源自身的无效表达式也不能作为通过结果。`verifyYsmRenderSetup` 在对应 Minecraft 类路径中执行渲染与反射成员初始化，检查八种材质组合、旋转/镜像/非均匀缩放下的顶点等价性、世界渲染目标（26.1/26.2）或 OIT 配套管线及键码转换（26.3/26.4-snapshot-1）。`verifyYsmQueries` 使用真实物品注册表、装备及药水组件，验证主副手短名称、默认命名空间、无效参数、普通箭/药水箭效果，以及船体自身的左右划桨与偏移；各版本的 `check` 均包含此项。`verifyModuleLibraries` 检查外部库更新、失败回滚及服务所有权；`verifyMinecraftTransformers` 检查实际游戏 jar 的注入位置。

自动化通过不能替代游戏画面验收。新宿主需要在游戏内核对：透明/发光材质、装备遮挡、第一人称持物、动作与参数切换、声音暂停/音量、换世界和反复热重载。测试模型覆盖不到所有作者自定义查询和动作组合，调试页会保留诊断。DetectOfflinePlayer 与其他模组兼容功能不属于本次修改。

```powershell
$env:GRADLE_USER_HOME='C:/Users/coffe/.gradle'
${env:ORG_GRADLE_PROJECT_kotlin.incremental}='false'
./gradlew.bat checkAllVersions ysmAllVersions benchmarkYsm '-Pmoons_build_directory=build/ysm-performance' --project-cache-dir build/ysm-performance/.gradle-project-cache --offline --no-parallel --no-daemon
```

独立输出目录用于避开旧产物的 Windows 文件锁。`ysmAllVersions` 逐版构建适配器并打包，不运行自定义验证，四个版本 zip 与一个通用 zip 集中输出到该构建目录的 `dist`。`ysmBundle` 构建当前 `-Pminecraft_version` 对应的包；公共库可单独构建 `ysmCoreJar ysmCodecsJar ysmImagesJar`。`compileAllVersions` 包含所有 YSM 适配器，`checkAllVersions` 包含 YSM 核心、材质及模块生命周期检查。原来重复执行的 `verifyYsmGameLibraries` 已合并到 `verifyYsmCore`。普通打包不强制执行自定义验证；CI 构建并上传版本包。详细命令见 [构建文档](../../../docs/BUILDING.md)。验证夹具不进入产物。

## 来源

Sparkle-Morpher `origin/fa26.1.2`，提交 `a52c36fc44ab239ffa4364eaea781785e0d2975f`。核心代码已改包名并移除平台/网络入口，MIT 许可位于核心 jar 的 `META-INF/licenses/Sparkle-Morpher.txt`。原项目内置模型有独立资源许可，本包不分发这些模型。图像库、音频库分别保留其来源与第三方许可。
