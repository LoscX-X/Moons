# Aim GRU 迁移训练包

适用：Windows 台式机，9950 / RTX 5080 / 96 GB RAM。先训练一个小型**连续动作预测器**，验证是否超过现有回归模型。GRU128 第 7 轮现已接入客户端可选的 `Learned assist` 辅助层，详见 [模型说明](../../src/libraries/models/aim/README.md)。

## 数据够不够？

**足够开始第一版小模型实验，不需要先追求更大的 CSV。**现有 legit.csv 为 4,416,329,486 字节、20,341,000 行、2,219 个 UUID 分组。此前按常规连续记录口径筛出约 1700 万行；这些行高度相关，不能当作 1700 万个独立瞄准案例，也不能把 UUID 数量直接当真人数量。

训练是否还需要更多数据，要看学习曲线和跨来源验证：扩大训练样本后，未参与训练的 UUID 是否持续改善？若训练误差下降、验证误差停滞，优先查过拟合和覆盖面。当前更需要明确目标 Pitch、采集位置和场景语义。

原文件有两项已确认的问题：Yaw 差分存在跨 ±180°边界问题；原始 accel_pitch 列存在混轴计算问题。因此本包从实际角度重新计算步长，使用步长之差，**不读取原始 accel_* 作为输入**。sensitivity 编码不明，也不作为输入。

## 1. 复制哪些东西？

1. 将训练包解压到任意可写目录，例如 `D:\AimTraining`。
2. 单独复制原始 `legit.csv`，例如 `D:\AimData\legit.csv`。ZIP 不含 4.42 GB 数据和 Python 运行环境。
3. 安装 **64 位 Python 3.12**，准备足够磁盘空间；建议留 10 GB 以上给数据、CUDA 版依赖和运行结果。GPU 依赖首次安装需要联网。

普通 RTX 5080 的官方显存是 **16 GB GDDR7**，以 `check_env.py` 检测到的实际硬件为准。16 GB 已足够这版模型。[NVIDIA 规格](https://www.nvidia.com/en-gb/geforce/graphics-cards/50-series/rtx-5080/)

## 2. 安装环境

在解压目录打开 PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1 -Device cuda
```

脚本在训练包目录创建 `.venv`，安装固定版本 NumPy、pandas 和 PyTorch 2.13.0 / CUDA 13.0，并实际执行一次 GPU GRU 前向、反向计算。请使用支持该 CUDA 运行时的 NVIDIA 驱动；出现驱动或架构错误时先看完整报错。安装来源及版本组合参考 [PyTorch 官方安装表](https://pytorch.org/get-started/previous-versions/)。这是项目固定环境，并不表示它始终是最新版。

若 `python` 不在 PATH，可指定真实解释器路径：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1 -Device cuda -Python 'C:\Python312\python.exe'
```

明确只用 CPU 时将 `-Device cuda` 改成 `-Device cpu`。切换安装模式会替换此虚拟环境的 PyTorch，不改系统 Python。脚本不需要激活 venv。

重新检测硬件：

```powershell
.\.venv\Scripts\python.exe check_env.py
```

## 3. 先做端到端自检

```powershell
.\.venv\Scripts\python.exe smoke_test.py smoke-check
```

自检使用小型合成数据，在 CPU 上检查分块边界、UUID 隔离、未来输入隔离、训练、模型重载、断点续训及连续回放。它不代表真实数据上的效果。输出目录需要尚不存在，重新测试可换名字。

## 4. 预处理真实数据（只需做一次）

```powershell
.\.venv\Scripts\python.exe prepare.py 'D:\AimData\legit.csv' data
```

数据分块读取，不修改原 CSV。输出 `data/manifest.json` 和分片 NPZ，训练时只需这些分片。完成标志为 `manifest.json`；途中失败的目录不能用于训练，应换一个新目录重新运行。

预处理规则：

- 对 UUID 做 SHA256，前 4 字节大端整数 `% 100`：0–69 训练，70–84 验证，85–99 测试。与已有回归基线一致，同 UUID 不跨集合。
- 每个 UUID 独立维护时间顺序；`new_sequence`、非正或不在 40–60 ms 的间隔、坏值及异常跳转切断片段。
- Yaw 使用最短角差，限制单步绝对值 ≤90°；Pitch 单步 ≤60°，角度本身在 ±90°内。原始 delta 列仅用于一致性核验。
- 保留距离 >0.05 的水平几何参考，暂不使用来源语义不明的目标 Y。
- 每个窗口保存 37 条有效动作记录，提供 16 条输入及最多 20 步评估；相邻窗口起点间隔 16 条。窗口之间有重叠，所以窗口数也不等于独立案例数。
- 分片保留 SHA256 分组标识用于评估，不保存原始 UUID 文本。先过滤后训练，结果只描述这个常规连续片段子集。

这比此前“四条记录即可参与分析”的口径更严格。长序列不足的片段不会产生训练窗口。训练输入只使用预测时刻之前的观察；未来记录用于标签和回放评估。

交付前全量运行实际得到：**训练 407,387、验证 62,525、测试 67,267 个窗口**，共 537,179 个。当前机器预处理耗时约 112.5 秒；这不是另一台电脑的性能承诺。

## 5. 训练

先做一次小规模流程试跑：

```powershell
.\.venv\Scripts\python.exe train.py train data runs\quick --device cuda --epochs 1 --steps-per-epoch 100
```

再做正式的初始实验：

```powershell
.\.venv\Scripts\python.exe train.py train data runs\gru48 --device cuda --epochs 20
```

默认配置：

| 项目 | 默认值 / 含义 |
| --- | --- |
| 网络 | 单层 GRU(7,48) + Linear(48,2)，8306 个可训练参数 |
| 观察窗口 | 最近 16 次观察，约 0.8 秒，按来源时间间隔理解 |
| 输入 | yaw/pitch 步长、两轴步长之差、水平目标参考误差、水平距离、已观察到的 dt |
| 输出 | 下一次 Δyaw / Δpitch，度/次；不是度/秒 |
| 归一化 | 只在训练集计算，作为模型 buffer 保存，在 forward 内执行 |
| 目标 | 按训练集两轴 RMS 缩放后的 SmoothL1；这是点预测，不生成完整动作分布 |
| 优化器 | AdamW，学习率 0.001，梯度范数上限 1 |
| Batch | 检测到 CUDA 显存 ≥8 GiB 时 2048，其余 512；可用 `--batch-size` 修改 |
| CPU 线程 | 8，可用 `--threads` 修改；不假定用满所有线程就最快 |
| 停止 | 最多 20 轮，验证分数连续 5 轮未改善提前停止 |
| 选模型 | 验证集两轴缩放后的平均绝对误差；不使用测试集选参数 |

需要逐轮比较连续回放时，训练命令加 `--keep-epochs`，保存 `epoch-001.pt` 等检查点。它只增加检查点保存，不改变训练目标、随机种子或样本顺序。

训练逐片读取到内存，片内打乱，不复制整个数据集到显存。96 GB RAM 有余量；小模型不一定能吃满 5080，先以日志中的实际吞吐量比较。`--steps-per-epoch` 非零表示每轮只运行指定批次数，不代表完整遍历训练集。

若显存不足，换新运行目录并使用 `--batch-size 512`。不建议为了让显卡满载就盲目扩大模型。

中断后从最近完整轮次继续，保持预处理数据、seed、batch、steps-per-epoch 和验证抽样数量一致：

```powershell
.\.venv\Scripts\python.exe train.py train data runs\gru48 --device cuda --epochs 20 --resume
```

`--epochs` 是总轮数。首次运行拒绝覆盖已有运行目录。续训使用检查点中的学习率、隐藏层和优化器状态；中断所在轮次会重做。

## 6. 看哪些结果？

| 文件 | 用途 |
| --- | --- |
| `runs/gru48/best.pt` | 验证一步预测分数最好的 PyTorch 检查点，含权重和归一化 |
| `runs/gru48/last.pt` | 最近完整轮次的检查点，含优化器，用于续训 |
| `runs/gru48/history.jsonl` | 每轮训练损失、验证误差、样本数和耗时 |
| `runs/gru48/validation.json` | GRU、保持角度、保持步长、两种岭回归在同一批窗口上的对照，以及 1/5/20 步回放 |
| `runs/gru48/model-metadata.json` | 特征顺序、单位、归一化和窗口要求 |
| `runs/gru48/environment.json` | 实际显卡、显存、设备、版本和批量 |

一步评估最多均匀抽取 20,000 个验证窗口，同时报告按行与按 UUID 平均的 MAE；回放每个验证 UUID 均匀抽一个窗口，避免大组独占结果。

**先看是否超过 persistence 和岭回归，再看 20 步误差有没有积累。**只有一步损失下降不能说明连续控制更好。`best.pt` 按一步分数选择，因此未保证也是连续回放最好的模型。

回放将预测角度反馈给模型，并继续输入数据中观察到的水平目标/玩家几何和已过去的时间间隔；它不是游戏物理仿真或未来世界预测。回放不裁剪输出，另外记录超出 ±90° 的 Pitch 次数。这里的角度误差不是命中率或任何检测系统的通过率。

确定模型设计后，再显式评估测试集：

```powershell
.\.venv\Scripts\python.exe train.py evaluate data runs\gru48\best.pt runs\gru48\test.json --split test --device cuda
```

本包默认训练流程不计算测试集成绩；此前旧回归实验已经看过其 UUID 划分的测试集，因此它也不是未来所有模型都可反复调参的全新独立数据来源。

## 7. 怎样调用训练出来的模型？

```powershell
.\.venv\Scripts\python.exe infer.py runs\gru48\best.pt example-input.json
```

示例是 16 行合成静止输入，只展示文件形状。真实输入按时间从旧到新排列，每行严格对应这 7 列：

通过打包脚本生成的训练包还提供 `verification/one-epoch-best.pt`，用于试跑推理；仓库不保存这些生成产物。从源码使用时，请先运行上方的 quick 训练，再使用生成的 `runs/quick/best.pt` 验证推理。

```text
[yaw步长, pitch步长, yaw本步与上步之差, pitch本步与上步之差,
 水平参考方向减当前yaw的最短角差, 水平距离, 上一次已观察间隔ms]
```

调用时传原始单位，不要预先重复归一化。不能把每帧调用当成训练数据中约 50 ms 一次的动作更新。

训练产物仍是 PyTorch 检查点。GRU128 第 7 轮另经 `export_java.py` 导出为 `src/libraries/models/aim/aim-gru128-epoch7.bin`，由客户端纯 Java 推理；无需 ONNX。已完成 PyTorch 数值对齐与状态测试，游戏内效果尚待验证。详见 [模型说明](../../src/libraries/models/aim/README.md)。

## 8. 这版实验解决什么、还缺什么？

它检验“短期历史 + 水平几何参考”是否能更好预测下一步动作。目标 Pitch 的语义仍未核实，所以不能仅靠增加训练轮数成为完整的双轴瞄准控制器。点预测还可能把多种合理动作平均化，降低误差并不等于复现真人动作分布。

原始来源：[FinalBoolean / Aim Dataset for Minecraft](https://www.kaggle.com/datasets/finalboolean/aim-dataset-for-minecraft)。作者说明目标位置有插值偏差，cheater 数据由 legit 合成；本包只处理 legit。要评估实际场景泛化，需要另外采集语义明确、不同来源的连续轨迹。

打包脚本会从本地构建结果生成随包 `verification/` 和 `SHA256SUMS.json`，两者均不纳入版本控制。本机 CPU 检查不等于已在目标 RTX 5080 上验证 CUDA 性能。到台式机后首先运行环境检查和 quick 实验。

## 9. 预计花多久？

- 初次安装：主要取决于 PyTorch CUDA 依赖下载速度，预留 5–20 分钟，网络慢时更久。
- 预处理：当前电脑实测全量约 112.5 秒；另一台电脑预留 2–5 分钟。
- 训练：本机 CPU、8 线程、batch 512，完整 407,387 个窗口的一轮训练及一步验证实测 **12.72 秒**，不含首次归一化、程序启动和最终连续回放。按此速度外推，20 轮训练主体约 4.2 分钟。
- 9950 + 5080 的 CUDA 训练尚未实测；为首个 20 轮实验预留 **5–15 分钟**，最终以 `history.jsonl` 的首轮耗时和提前停止情况为准。小模型可能受批次调度和数据读取限制，不能按显卡算力直接推导加速比。

环境安装好之后，预处理和首个正式实验合计先按 **10–20 分钟**安排。
