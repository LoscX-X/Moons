# 参与开发

环境配置和打包命令见 [README](readme.md)。报告问题时，请附上 Minecraft 版本、复现步骤和相关日志。

## 修改代码

- 两个版本共用的逻辑放在 `src/minecraft/shared`；Minecraft API 和注入位置的差异放在 `src/minecraft/versions` 对应目录。
- `src/bootstrap` 是独立加载层，不依赖 Minecraft 或客户端功能类。
- 涉及静默旋转、物品栏或模拟输入时，使用已有的协调入口，避免多个模块互相覆盖状态。
- 重构与功能调整尽量分开，方便判断行为变化。

提交前可用仓库自带的格式化任务统一风格：

```powershell
.\gradlew.bat formatCode
```

## 验证与提交

按改动范围选择编译、相关验证或游戏内测试。文档修改不需要构建，局部修改也不要求跑全套检查。

需要确认两个 Minecraft 版本整体兼容时，运行：

```powershell
.\gradlew.bat checkAllVersions
```
