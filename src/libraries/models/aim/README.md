# Aim 模型

> [!WARNING]
> **安全问题请提供代码与证据。**
>
> 如您认为本项目存在任何安全问题，请立即 [提交 Issue](https://github.com/LoscX-X/Moons/issues/new)，并注明对应代码位置（文件路径及行号）、受影响版本、复现步骤和相关证据。
>
> Moons 自身代码未主动混淆，源码公开可审查。这一说明仅针对 Moons 自有代码；Minecraft 名称映射和第三方组件请按各自说明核查。
>
> 我们欢迎有依据的质疑。请基于代码和可验证事实讨论，不要将未经验证的推测当作已经证实的问题。

SilentAura 的可选 `Learned assist` 辅助模型，为 Lock、Balance 和 FullLock 提供小幅转向修正，默认关闭。在 SilentAura 中开启 `Learned assist`，通过 `Learned strength` 调整强度（默认 0.35）。

- 结构：单层 GRU，7 维输入、128 维隐藏状态，使用过去 16 条有效发送观测预测下一步 Yaw / Pitch 增量。
- 权重：`aim-gru128-epoch7.bin`，52,866 个参数，211,588 字节；选自第 7 轮训练。
- 元数据：`aim-gru128-epoch7.json`，记录模型结构、输入特征和 SHA-256。

构建时复制到 JAR 的 `assets/moons/models/`，运行时由纯 Java 加载和推理，无需 Python、PyTorch 或 GPU。辅助关闭、预热或加载失败时使用原模式结果。
