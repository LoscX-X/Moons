# SilentAura 转头组件模拟

2026-09-15。使用实际 SmoothA、PacketRotationSmoother、AimSamplingA 和 QuantizerA，完成 5208 次可复现运行。测试条件、全部参数网格、指标定义和限制详见 [参数说明第 9 节](E:/McEnv/moons/docs/SilentAura-LiquidBounce-参数详解与重构方案.md#rotation-simulation)。

## 文件

- [逐组、逐种子结果快照](E:/McEnv/moons/docs/silentaura-simulation/summary.csv)
- [精选轨迹](E:/McEnv/moons/build/rotation-simulation/traces.json)：由模拟任务生成，清理 build 会删除。

单位写在 CSV 列名中；到达时间 −1 表示不适用或没有在观察窗口内稳定。每个结果均来自模拟成功发送并量化后的角度。

## 结果摘要

- 对角 90°/45°、60 FPS、灵敏度 0.5，Lock / Optimize 关的 Smooth=0.58、0.8、1.0，8 个种子的平均稳定到达时间都为 456.25 ms。到达时间相同不代表轨迹完全相同。
- 同样条件下，Balance / Optimize 关的 Smooth=0.05、0.58、1.0，平均稳定到达时间分别为 487.50、812.50、825.00 ms；提高响应不保证更早稳定。
- 近身裁剪场景中，Optimize 开的 Lock 在第 4 次更新将 Yaw 步长从约 48° 裁至 18°，出现约 12000°/s² 的减速度幅值，超过该模式普通 9200°/s² 预算外包上界。
- FullLock 的 Smoothing 缩小角度步长，不提供独立加速度限制。

## 覆盖边界

这是隔离组件实验：固定帧级 stepScale=1，不执行噪声 correction、预测、目标选择、射线走廊保点或攻击。没有游戏内验证，不能把结果当成命中率或服务器效果。此次只改变界面 Optimize 命名，保留旧配置键；旋转算法默认行为保持原样，增加了可注入的随机源用于复现。

验证：26.1.2 下编译、模拟内部重放断言和 verifySilentAuraPoints 均通过。未为这次命名及模拟重新执行完整多版本 check；上一阶段 AimPoint 的三版本验证记录见主文档。
