# 参与开发

环境配置和打包命令见 [README](readme.md)。报告问题时，请附上 Minecraft 版本、复现步骤和相关日志。

## 修改代码

- 多个版本共用的逻辑放在 `src/minecraft/shared`；Minecraft API 和注入位置的差异放在 `src/minecraft/versions` 对应目录。
- `src/bootstrap` 是独立加载层，不依赖 Minecraft 或客户端功能类。
- 涉及静默旋转、物品栏或模拟输入时，使用已有的协调入口，避免多个模块互相覆盖状态。
- 涉及 Blink、出站延迟或入站缓冲时，复用 [Blink / LagUtils 公共接口](NETWORK_DEVELOPMENT.md)，统一处理回放与连接生命周期。
- 重构与功能调整尽量分开，方便判断行为变化。

提交前可用仓库自带的格式化任务统一风格：

```powershell
.\gradlew.bat formatCode
```

## 验证与提交

按改动范围选择编译、相关验证或游戏内测试。文档修改不需要构建，局部修改也不要求跑全套检查。

日常 CI 只编译正式源码和按需打包，不运行自定义验证。可在本地使用同一编译入口：

```powershell
.\gradlew.bat compileAllVersions
```

目前只保留 Minecraft 映射和注入点验证；其他自定义验证程序已移除。在 Actions 手动运行工作流并勾选 `verify`，或在本地运行以下命令，编译正式源码并验证各版本映射：

```powershell
.\gradlew.bat checkAllVersions
```
