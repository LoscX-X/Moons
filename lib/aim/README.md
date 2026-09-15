# SilentAura Learned assist

当前模型是 **Lock / Balance / FullLock 的可选辅助层**。旧的独立 `learned_128` AimMode 已移除；保存了旧模式的配置读取时回到 Lock，辅助开关默认关闭。

## 启用与参数

加载新版客户端，在 SilentAura 保留你要使用的 Aim mode，打开 **Learned assist**，再调 **Learned strength**。

| 参数 | 默认 | 含义 |
|---|---:|---|
| Learned assist | 关闭 | 对原模式最终步长应用可选模型修正；松开攻击后的回正不参与。 |
| Learned strength | 0.35 | 0–1；控制修正幅度。0 完整使用原模式结果。 |

原来的 Smooth、Optimize、FullLock 等参数继续属于原模式。原独立模型的四个速度/加速度参数已移除，辅助层服从所选模式本身的步长和加速度约束。

## 接入位置与边界

`选目标 / AimPoint → 原 AimMode 的帧级转向 → 原模式包级步长 → 模型小幅修正 → 原鼠标量化 / RotationLease → 确认发送历史`

- 模型使用过去 16 条有效发送观测预测下一步角度增量。不会把渲染帧或尚未发送的候选角加入历史。
- 预测与原步长、真实目标误差同向时才考虑修正。预测方向冲突、原模式已经停住、距离目标角误差 ≤1° 时保持原结果；1–5° 内逐渐减弱修正。
- Yaw 修正预算为 `min(1.5°, |原步长| × 25%) × strength × 接近目标衰减`。默认强度下最多 0.525°/tick，Pitch 使用一半预算。不再额外叠加上一版的 Pitch 误差校正器。
- 最终修正还必须落在原模式的速度/加速度范围内，不改变旋转方向，也不把本步原本未越过目标的输出推过目标；这不是全轨迹零超调保证。原模式本身若因死区或近身限幅存在突变，辅助层不会扩大该突变。
- 以上角度预算位于最终鼠标量化之前；最终可见变化可能被量化舍掉，不能将这些值当作量化后严格差值。
- 关闭、强度 0、预热、无效预测、加载失败均直接使用原模式结果。同一 tick 已提交角度不被中途开关重新改写。
- 目标/世界切换、明显位置跳变、发送 tick 中断/倒退、角度基准变化、灵敏度或强度变化时清理辅助历史。

## Debugger 状态

- `warmup N/16`：继续原模式，收集模型历史；约 18 次连续有效发送后完成窗口，下一 tick 可推理。
- `active`：本 tick 使用了修正（最终量化仍可能将小修正舍掉）。
- `ready: primary retained`：模型可用，但当前条件不允许修正或修正无变化。
- `bypass: ...`：模型异常，本 tick 继续原模式。
- `idle`：辅助关闭、目标丢失或状态刚重置。

有效历史要求相邻发送约 40–60 ms。静止时未连续发包、帧卡顿、频繁切换目标都可能导致预热反复；这种情况下原模式仍正常运行。

## 模型文件与构建

权重仍放在 `lib/aim/`，构建复制到 feature JAR 的 `assets/moons/models/`。运行时通过类加载器读取，无需外部路径、Python、PyTorch 或 GPU。

- `aim-gru128-epoch7.bin`：211,588 字节，GRU(7,128)，52,866 个参数。
- `.json`：结构与来源信息，权重本身在 `.bin`。
- 来源：`outputs/aim-gru-local-20260915/gru128/best-rollout.pt`，第 7 轮验证集回放优选。
- 加载器固定结构并校验 SHA-256。改换模型要重新导出并同步校验及数值对齐测试。

```powershell
& build/aim-training-venv/Scripts/python.exe tools/aim_training/export_java.py outputs/aim-gru-local-20260915/gru128/best-rollout.pt build/aim-gru-data-verified lib/aim/aim-gru128-epoch7.bin src/test/render/resources/aim-gru128-parity.bin
```

`verifyLearnedAim` 包含 1,026 组 PyTorch 数值对齐、三种原模式回退一致性、方向/修正预算/运动范围检查、发送历史和状态重置。测试资源不会打包进客户端。

## 效果范围

上一版独立控制器的游戏内反馈较差，因此改成受限辅助。当前检查验证实现和边界，并不证明更拟人、命中率提升或服务器适用性；仍需要针对具体模式进行游戏内对比。模型没有目标 Pitch 输入，也不是针对原控制器误差训练的残差模型，这仍是保守的实验性用法。
