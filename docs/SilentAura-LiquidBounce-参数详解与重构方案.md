# SilentAura 与 LiquidBounce KillAura：参数详解、Smooth 分析与重构方案

更新日期：2026-09-15。本文依据本机源码逐项核对，并补查官方仓库历史记录。最初仅整理方案；随后按用户确认，已完成 AimPoint 第一阶段：保留 Center／Closest 的原有算法，加入分别可开关的 Lazy 和 Gaussian。本轮将 Limitation 界面名称改为 Optimize，加入可复现的转头组件模拟；其余全面重构尚未实施，当前进度见第 8、9 节。

## 阅读范围与结论

- 我们：`E:/McEnv/moons` 的 SilentAura；仓库 HEAD 为 `8a4650fb087776031f33e94651a59bbe175bbab3`。
- 参考：`E:/McEnv/skid/lb/LiquidBounce` 的 **KillAura**，不是名为 SilentAura 的模块；HEAD 为 `e67fdf9b70131f478c5894cf9de1f2d4aa7a95ce`。
- 第 7 节另外核对本机 `origin/legacy` 的 b100 快照：`03bc91a3c34031d1ea7400ef3b67c00adf6a4c6e`（2025-03-02）。这是本地保存的分支状态，不宣称是远端今天的最新代码；不与第 3 节 Nextgen 参数表混用。
- 参数表列的是**源码声明的默认值**，不是正在运行的客户端配置。范围表示允许设置的范围，`a–b` 表示上下限或随机区间，具体含义见该行。
- LB 范围包括 KillAura 本体及它注册的 Clicker、Range、Target、Rotations、AimPoint、AutoBlocking、FailSwing、FightBot、RangeIndicator、TargetRendering 子树。独立模块 AutoWeapon、Criticals、Teams、AntiBot 的全部设置不属于 KillAura 参数，不混入本表。
- 通常 20 tick/s，1 tick 约 50 ms；卡顿和实际事件调用频率会影响墙钟时间。Yaw 是水平朝向，Pitch 是俯仰。
- **我们的 Smooth 主要调整帧级响应；包级另有限速/加速度处理，但没有统一约束最终发包的 jerk 和统计规律。因此“看起来慢了”不能证明“特征量都降了”。**
- 重构重点应是统一转向轨迹与时间基准、独立选敌/选点/攻击职责、明确参数单位，再以最终已发送角度验证效果。
- **Polar 历史上有针对性工作**：Legacy 的 LazyFlick 提交明确提到 Polar；本机 Nextgen 的 KillAura 未发现独立 Polar 转向模式。历史提交、配置存在与当前服务器效果需要分别判断，详见第 6 节。
- 本文把用户提到的“Failure”按 `Rotations.Fail` 和 `FailSwing` 两条路径解释：前者改变朝向，后者负责符合条件时的空挥，均不等于固定攻击失败率。

## 目录

1. [我们的全部参数](#moons-parameters)
2. [Smooth 到底平滑在哪里](#smooth-analysis)
3. [LiquidBounce 的全部模块参数](#lb-parameters)
4. [两边差异与重构方案](#refactor-plan)
5. [源码索引与核对边界](#source-index)
6. [Polar 历史与高版本 Failure 的实际设计](#polar-failure)
7. [Legacy：AngleChange、YawRandomization 与 Legitimize](#legacy-rotation-details)
8. [AimPoint 第一阶段：已实现的 Lazy 与 Gaussian](#aimpoint-implementation)
9. [Optimize 命名、角速度/加速度与 Smooth 饱和实验](#rotation-simulation)

本轮重点：[AimPoint 第一阶段](#aimpoint-implementation)、[步长与 Balance 优化](#balance-redesign)、[全面重构的接口和验收](#implementation-contract)、[Fail 与空挥的区别](#polar-failure)。第 1、3 节是现状参数，第 4 节是全面方案，第 8 节记录本次已落地的范围。

<a id="moons-parameters"></a>

## 1. 我们 SilentAura 的全部参数

以下配置键统一省略前缀 `silentaura.`；例如 `smooth` 完整键是 `silentaura.smooth`。界面名称与配置键可能不同，表中同时列出。

总定义来源：[SilentAuraConfig.java](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraConfig.java)。

### 1.1 启用、攻击与格挡

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| Enabled / `enabled` | 关 | 模块总开关。启用之后，还必须处于可操作游戏状态、手持剑或斧，并物理按住攻击键，才进入主动追踪/攻击。 |
| Combat mode / `combatMode` | `latest`；`legacy/latest` | `latest` 按武器蓄力和 Critical 联动调度；`legacy` 按 CPS 调度并使用独立格挡流程。切换它不会把客户端网络协议变成 1.8。 |
| Clicks per second / `minCps`、`maxCps` | 10–14；各 1–20 | Legacy 使用的随机点击频率区间。格挡、射线、物品使用等条件可能让实际攻击频率低于该值。Latest 不使用这两个参数。 |
| Attack charge / `minCharge`、`maxCharge` | 0.70–1.00；各 0.70–1.30 | Latest 每轮抽取的攻击蓄力阈值。1 表示满蓄力；大于 1 是满蓄力后额外等待，不是伤害增加到 130%。Critical 调度也参与最终攻击时机。 |
| Critical / `critical` | 开；仅 Latest | 联动现有 Critical 模块的自动攻击判定/预约流程。不是一个独立的“必定暴击”开关。 |
| Auto block / `block` | 开 | Legacy 进入真实使用/解除/攻击/重新格挡流程，实际能否格挡还取决于持物和版本能力；Latest **仅显示剑的格挡姿态**。 |
| Visual blocking / `block.visual` | 开；仅 Legacy 且 Auto block 开 | 控制 Legacy 格挡与命中反馈动画。它不是真实格挡开关，Latest 的视觉姿态也不读取它。 |
| Block range / `block.range` | 4.5 格；1–8 | 已锁定目标在此距离内才进入对应格挡/视觉判定。它不会扩大扫描范围或攻击距离。 |

实现依据：[激活条件](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraRuntime.java)、[LatestCombat](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/latest/LatestCombat.java)、[LegacyCombat](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/legacy/LegacyCombat.java)、[LatestBlock](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/latest/LatestBlock.java)、[LegacyBlock](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/legacy/LegacyBlock.java)。

### 1.2 距离、选敌与目标类别

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| Attack range / `range` | 3.7 格；1–6 | 请求的攻击距离。实际值为 `min(设置值, 玩家实体交互距离属性)`。如果玩家属性是 3，填写 3.7 仍按 3 处理。距离按眼睛到命中盒判定，不是到实体坐标中心。 |
| Through blocks / `throughBlocks` | 关 | 选点和攻击射线忽略方块遮挡；仍检查距离和射线是否穿过目标命中盒。当前同一开关同时控制“隔墙追踪”和“隔墙攻击”。 |
| Scan range increase / `scanExtra` | 2.5 格；0–7 | 提前发现/追踪目标的额外距离。扫描范围 = 实际攻击距离 + 此值。进入扫描范围并不代表允许攻击。 |
| FOV / `fov` | 180°；1–360° | 以玩家相机视线为基准的**总角度**，允许偏离角度是该值的一半。180 表示最多偏离 90°；360 才是完整方向范围。使用空间夹角，也受俯仰影响。保留目标时也检查 FOV。 |
| Target mode / `targetMode` | `switch`；`switch/single` | `single` 尽量保留仍合格的当前目标；`switch` 在成功攻击当前目标后，最早下一 tick 重新排序。**Switch 不保证换人**：同一个目标仍可能排名第一。 |
| Maximum hurt time / `hurtTime` | 10；0–10 tick | 新目标获取时允许的最大受伤计时；越小越倾向等待目标的受伤计时下降。**当前实现漏洞：保留已有目标的检查和最终攻击路径没有同样检查此参数，不能理解为严格的每次攻击门槛。** |
| Target players / `target.player` | 开 | 是否允许玩家目标。最终还经过公共目标过滤逻辑。 |
| Other entities / `target.entities` | 空集合 | 指定允许的其他实体类型 ID；空集合表示未额外选择非玩家实体。是实体类型列表，不是玩家名字列表。 |

补充：

- 当前排序优先考虑能攻击的候选，然后是类型、距离、相机夹角、生命值加吸收血量、hurtTime、原目标、实体 ID。**没有一个可配置的“按血量/距离/方向优先”参数。**
- Single 也不等于永不换目标：当前目标失效、不可见，或者可攻击候选优先规则排除它时，都可能丢失/更换目标。
- 最终攻击射线可能选择视线上更靠前的其他合格生物。界面锁定目标不一定始终就是攻击对象。

实现依据：[CombatReach](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/combat/CombatReach.java)、[TargetSelectorA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/TargetSelectorA.java)、[SilentAuraTargets](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraTargets.java)、[SilentAuraAttackRay](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraAttackRay.java)。

### 1.3 瞄准模式、平滑与回正

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| Aim mode / `aimMode` | `lock`；`balance/lock/full_lock` | Balance 响应较软，包含帧级速度惯性、更多落点微动；Lock 响应更快，优先维持命中射线；FullLock 使用中间走廊选点和单独的包级角度步进。三者不只是同一速度滑块的三个预设。 |
| Smooth / `smooth` | 0.58；0.05–1 | Lock/Balance 的帧级响应系数。**越大越快、越紧跟目标；越小越迟缓。** 不直接设置包级最大角速度或加速度，不参与 FullLock 主动追踪。详见第 2 节。 |
| Aim point / `aimPoint` | `center`；`center/closest` | Center 实际是偏上半身、跟随眼高的落点策略，可带目标局部漂移，**不是固定命中盒几何中心**；Closest 使用靠近眼睛、略向盒内收缩的点，并短时保留目标局部锚点。FullLock 不读取这项选择。 |
| Return rotation / `returnRotation` | 开 | 松开攻击键、丢失目标或正常关闭模块时，让静默朝向逐渐回到相机方向。涉及的是静默/服务端朝向的释放过程。 |
| Return smooth / `returnSmooth` | 0.45；0.05–1 | 回正响应和回正速度预算。**越大回正越快。** 与主动追踪 Smooth 独立；只在 Return rotation 开时显示。 |
| Optimize / `matrix` | 关；Lock/Balance | 原界面名称 Limitation。内部仍叫 Matrix compatibility，沿用保存键，会选用更严格的包级加速度配置，并影响近距离交叉目标处理。不是一个统一的平滑强度，也不是效果保证；FullLock 下强制不启用。 |

### 1.4 漂移与抖动

下面的“强度”都是实现内部归一化系数，不是直接的度数。

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| Path jitter / `jitter` | 0.38；0–1 | 转向/选点过程中的噪声强度，也影响部分响应/加速度变化。Lock 的噪声缩放比 Balance 小。降低到 0 不等于清除整个系统的全部随机性。FullLock 主动追踪不使用这条路径。 |
| Jitter speed / `jitterSpeed` | 0.85；0.1–3 | 噪声内部时间推进倍率。越大变化越快；不是角色转头速度。 |
| Settled sway / `settledJitter` | 0.55；0–1；仅 Balance 显示 | 接近目标后的局部游移和到达时减缓程度；与 Path jitter 等相乘/联动，不是完全独立的抖动通道。 |
| Aim wander / `aimWander` | 0.45；0–1 | Center 策略中目标局部锚点的漂移幅度。Closest 不使用这个幅度；Lock 已经保住命中射线时通常优先保留射线点，因此此值不一定明显生效。 |
| Wander interval / `aimWanderTicks` | 9 tick；2–40 | Center 随机锚点更新间隔的基准，实际区间约为设定值的 0.65–1.35 倍并取整；Closest 用它推导保持时间，规则不同。不是每隔固定 9 tick 必然跳一次点。 |

实现依据：[AimPointsA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimPointsA.java)、[AimPointsB](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimPointsB.java)、[AimPointsD](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimPointsD.java)、[AimSolverB](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimSolverB.java)。

### 1.5 FullLock 专用参数

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| Full-lock angle step / `fullLock.angleStep` | 90°；30–180° | 每 tick 候选角度在每个轴上的步长上限。Yaw/Pitch 各自裁剪，不是二维合成角度的总预算。 |
| Full-lock smoothing / `fullLock.smoothing` | 0；0–1 | 步长倍率为 `1 - 0.5 × smoothing`。0 使用完整步长，1 使用一半。**它没有用上次速度/加速度构造连续轨迹，不能理解成 jerk 平滑器。** |
| Full-lock lead ticks / `fullLock.prediction` | 1 tick；0–3 | 水平移动预测时间。预测点限制在当前命中盒内部并重新检查遮挡；不是瞄准到任意远的未来坐标。 |

**隐藏参数问题：** FullLock 的 UI 隐藏了下面的通用运动预测参数，但它仍调用同一个 `motionPredictionParameters()` 构造预测器。因而在 Lock/Balance 改过速度、加速度、最大预测时间、响应等设置后，切到 FullLock 仍可能受这些隐藏设置影响。FullLock 最终落点只取预测位移的 X/Z，不能把通用的垂直权重理解为它会主动增加垂直提前量。

实现依据：[SmoothJ](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothJ.java)、[AimSolverC](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimSolverC.java)、[SilentAuraRotationController](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraRotationController.java)。

### 1.6 预测参数

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| Velocity lead / `predictionLead` | 0.75；0–2 | Lock/Balance 的基础提前系数。与预测强度、模式倍率、估计转向时间共同确定预测 tick 数，**不是直接固定预测 0.75 tick**。 |
| Turn prediction / `predictionStrength` | 1；0–3 | 预测总强度之一，同时影响位置提前、水平角速度前馈和部分俯仰预测。名称里的 Turn 不等于只预测目标转弯。 |
| Max target speed / `motion.maxSpeed` | 1.5 格/tick；0.1–3 | 目标运动预测器的速度限制，也参与不连续观测识别。不是自己的转头速度。 |
| Max target acceleration / `motion.maxAcceleration` | 0.12 格/tick²；0–1 | 预测目标位移时的加速度限制。不是 Yaw/Pitch 转向加速度。 |
| Max lead ticks / `motion.maxHorizon` | 3 tick；0–6 | 目标位置预测最长时间。0 会消除该预测器的前向位移预测。 |
| Vertical lead weight / `motion.verticalScale` | 0.35；0–1 | 垂直预测位移权重。0 抑制垂直提前，1 保留完整垂直权重。 |
| Max movement turn / `motion.maxTurnRate` | 0°/tick；0–90 | 目标移动方向转弯的预测上限。0 表示关闭转弯外推；不是限制实体实际运动，也不是自己 Yaw 的每 tick 上限。 |
| Max predicted turn / `motion.maxTurnAngle` | 90°；0–180 | 整段预测轨迹累计允许转弯的角度上限。只有上面的转弯预测开启时才显示。 |
| Prediction response ticks / `motion.minResponse`、`motion.maxResponse` | 0.35–1.5 tick；各 0–3 | 预测器速度估计的平滑响应时间范围。**越小越快追随新测量，越大越迟缓；0 可取消相应速度平滑。** 这和界面 Smooth 的增大方向相反。 |

Velocity lead 的主要组合关系可简化为：

`基础提前 = predictionLead × predictionStrength × 模式倍率`

随后根据当前转向误差、模式速度和响应估计需要提前多久，再截断到 Max lead ticks；落点还会被限制在可用命中盒区域内。窄小可见区域等分支会抑制预测，以免把可见点推到遮挡后面。

实现依据：[MotionPrediction](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/prediction/MotionPrediction.java)、[AimPrediction](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/prediction/AimPrediction.java)、[AimSolverB](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimSolverB.java)。

### 1.7 调试

| 界面/配置键 | 默认值 | 实际含义 |
| --- | --- | --- |
| Debugger / `debugger` | 关 | 显示激活状态、目标、模式、候选角度射线、已发送角度射线、交叉状态、攻击受阻原因、Critical 状态和计划。**目前没有输出速度/加速度/jerk 的统计图或分位数。** |

来源：[SilentAura 调试 HUD](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/SilentAura.java)。

### 1.8 新增：AimPoint Lazy / Gaussian

新增设置适用于 Latest／Legacy 的 Lock、Balance、FullLock，两个效果默认均关闭。配置键仍省略 `silentaura.` 前缀。

| 界面/配置键 | 默认值；范围 | 实际含义 |
| --- | --- | --- |
| AimPoint Lazy / `aimPoint.lazy` | 关 | 新基础点相对当前局部保持点的变化未达到阈值时，保持原来的局部位置。目标移动时保持点立即跟随当前命中盒。 |
| Lazy threshold (blocks) / `aimPoint.lazyThreshold` | 0.15 格；0.01–0.4 | 每新 tick 比较基础点与保持点的距离；达到或超过阈值就接受新点。保持点被遮挡或超距时立即失效，不等待阈值。 |
| AimPoint Gaussian / `aimPoint.gaussian` | 关 | 用有界的高斯采样生成局部瞄准偏移；分别控制水平和垂直强度。 |
| Horizontal stddev (blocks) / `aimPoint.horizontalDeviation` | 0.05 格；0–0.3 | X/Z 每轴采样的标准差，0 关闭水平偏移。原始采样截到 ±3 倍标准差；这里是格数，不是角度。 |
| Vertical stddev (blocks) / `aimPoint.verticalDeviation` | 0.025 格；0–0.2 | Y 轴采样标准差，0 关闭垂直偏移。分布以 0 为中心，上下对称。 |
| Offset response / tick / `aimPoint.offsetResponse` | 0.2；0.01–1 | 每 tick 消除多少“当前偏移与目标偏移”的差距；越大偏移变化越快。1 可直接到新偏移，不保证运动连续性。 |
| Offset interval (ticks) / `aimPoint.offsetInterval` | 8 tick；1–40 | 重抽目标偏移的间隔；通常 20 TPS 下 8 tick 为 0.4 秒。每次渲染不会重抽。 |

Lazy 子项仅在 Lazy 开启时显示；Gaussian 子项仅在 Gaussian 开启时显示。FullLock 也可以调整这些新选点效果；本次没有改变 FullLock 原来的基础选点或预测算法。

来源：[SilentAuraConfig](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraConfig.java)、[SilentAuraPointProcessor](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraPointProcessor.java)。

<a id="smooth-analysis"></a>

## 2. 我们的 Smooth 到底平滑在哪里

### 2.1 实际处理链

`目标/可见落点 → 锚点、噪声、预测 → 帧级追踪角度 → 每 tick 包级步进 → 鼠标灵敏度量化 → RotationLease 提交 → 实际发送确认`

对应关键代码：

1. [AimProfileA/B](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimProfileA.java)：将 Smooth 转成模式响应。
2. [AimSolverB](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimSolverB.java)：叠加可见性紧迫度、预测、微动和响应变化。
3. [AimStepA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimStepA.java) → [SmoothA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothA.java)：按帧时间推进追踪角度。
4. [PacketRotationSmoother](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/PacketRotationSmoother.java) → [SmoothF/K](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothK.java)：依据上次实际发送的角度步长产生本 tick 候选。
5. [QuantizerA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/quantize/QuantizerA.java)：把角度差舍入到鼠标灵敏度步进格点。候选值与最终发包值必须区分。

### 2.2 Smooth 的直接数学作用

令界面值为 `s`，基础响应为 `k`：

- Lock：`k = 19 + 23s`。
- Balance：`k = 12 + 16s`。
- 每帧指数响应比例：`α = 1 - exp(-k × dt)`。
- 简化追踪步长：`step ≈ angleError × α`，随后还有速度裁剪、Yaw 前馈及部分模式的惯性处理。

因此：

| Smooth | Lock 基础 k | Balance 基础 k |
| --- | --- | --- |
| 0.05 | 20.15 | 12.80 |
| 0.58，默认 | 32.34 | 21.28 |
| 1.00 | 42.00 | 28.00 |

**这个滑块更接近“Tracking response / 跟随响应”，而不是“越大越平滑”。**

它也间接参与预测时间估计：响应减慢时，公式可能给出更长的提前时间。所以调低 Smooth 会同时改变跟随与预测的关系，不能仅理解为所有输出乘一个较小系数。

### 2.3 哪些量确实有限制

| 阶段 | 已有机制 | 实际边界 |
| --- | --- | --- |
| Lock 帧级追踪 | 指数逼近、每帧步长预算 | 基础 Yaw/Pitch 速度为 1080/760°/s，另乘动态缩放。此路径没有 Balance 那样的帧级速度惯性。 |
| Balance 帧级追踪 | 指数逼近、速度惯性、梯形积分 | 基础 Yaw/Pitch 速度为 660/440°/s；惯性中的基础加速度是 3600/2600°/s²，另乘动态缩放。 |
| Lock/Balance 包级步进 | 根据上次确认步长限制步长变化，并用剩余误差估计刹车速度 | 正常情况下 Yaw/Pitch 目标步长上限 48/32°/tick。加速度预算由模式、紧迫度和随机采样决定，**不直接读取 Smooth**。 |
| Optimize（原 Limitation） | 更严格的包级加速度采样 | 特别收紧 Pitch，且部分重叠目标场景进一步裁剪 Yaw；不能把这一开关等同于完整的三阶平滑。 |
| FullLock | 每轴角度裁剪，再乘 `1 - 0.5s` | 专用 Smoothing 不读取上次角速度/加速度，没有独立的加速度、jerk 连续性约束。 |
| 最终量化 | 输出落在灵敏度格点上 | 量化可能带来零步长、一个格点的跳动以及差分变化；量化前的约束不自动等于最终序列的严格约束。 |

包级采样器会把新的加速度预算与上次预算按 0.3 比例混合；这会缓和**预算**变化，但它并不是对“最终已发送角度三阶差分”施加硬上限。

### 2.4 降速是否把“特征量”降下去

不能一概而论，需要先说清楚是哪一项。用固定 tick 间隔的已发送角度 `θ[t]` 定义：

- `v[t] = wrap(θ[t] - θ[t-1])`：每 tick 转过的角度。
- `a[t] = v[t] - v[t-1]`：角度步长的变化。
- `j[t] = a[t] - a[t-1]`：加速度变化，通常叫 jerk。
- 实际发送间隔不均匀时，还需要按时间差换算，不能把逐包差分直接当成固定 tick 的速度。

降低这些量的平均幅度，与降低重复规律、相关性、极端尖峰，是不同目标。例如：

| 示例步长序列，非实测 | 平均绝对步长 | 相邻步长变化 |
| --- | --- | --- |
| 4、4、4、4 | 4 | 0、0、0 |
| 2、-2、2、-2 | 2 | -4、4、-4 |

第二组“更慢”，但加速度变化明显更大。反过来，一条完全固定比例衰减的曲线可能非常光滑，同时仍非常规律。

对当前代码能下的结论是：

1. Smooth 降低时，部分场景的跟随步长会变小；大误差、前馈、限速、选点变化会改变这种效果。
2. 包级加速度机制已经存在，但它与 Smooth 不是同一个控制器，不能保证所有调参结果单调变小。
3. 没有统一的最终 jerk 约束；换目标、落点切换、遮挡恢复、交叉目标和回正都需要独立检查。
4. Path jitter、锚点漂移、响应变化、包级随机预算是不同随机来源；增加随机性也可能放大高频差分。
5. 当前没有本次运行采集的角度序列、对照统计或检测器输出，因此**不能声称已经降低了某种统计特征或通过了某项检测**。

### 2.5 重构时如何验证 Smooth

建议采集最终确认发送的角度，而不是渲染头部角度，并同时记录：

- tick、时间戳、目标 ID、相机角度、期望角度、量化前候选、最终发送角度；
- v/a/j 的峰值和 95%、99% 分位数；
- 连续零步长、方向反转、重复步长、Yaw/Pitch 相关性；
- 射线命中率、目标误差、攻击等待原因和收敛时间。

使用同一段移动输入，对照静止目标、横移、反向横移、双方跳跃、近距离穿身、局部遮挡、换目标、松手回正；再对比不同 FPS 下的最终输出。只有这些结果，才能回答“降的是哪一种特征，代价是什么”。此处是验证方案，尚未实施或实测。

<a id="lb-parameters"></a>

## 3. LiquidBounce KillAura 参数

本节使用 LB 的分组路径。不同分组里的同名参数不是同一个设置。例如 `Target.HurtTime` 过滤目标，而 `AimPoint.Gaussian.Dynamic.HurtTime` 是动态偏移条件。

子分组的 `Enabled` 是该分组开关；关闭时通常保留配置，只是不运行对应功能。只统计实际注册的设置，注释掉的参数和运行时计数器不算用户参数。

### 3.1 模块本体与要求条件

来源：[ModuleKillAura.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/ModuleKillAura.kt)、[KillAuraRequirements.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/KillAuraRequirements.kt)。

| 参数 | 默认值 / 可选值 | 含义 |
| --- | --- | --- |
| Enabled | 关 | KillAura 总开关。 |
| Bind | 未绑定；默认 Toggle 动作 | 模块快捷键及触发动作，来自模块基类。 |
| Hidden | 关 | 是否从启用模块显示列表中隐藏，来自模块基类。 |
| Requires | 空集合 | 额外运行前提。选中的条件必须**全部满足**，空集合表示不额外要求。 |
| Requires.Click | 未选 | 需要攻击键当前按下，或最近 250 ms 内按下过。 |
| Requires.Weapon | 未选 | 需要符合武器判断：剑；新协议允许斧；也考虑重锤和带击退附魔的物品。 |
| Requires.EmptyHand | 未选 | 需要空手。和 Weapon 同选通常互相冲突。 |
| Requires.VanillaName | 未选 | 主手物品没有自定义名称。 |
| Requires.NotBreaking | 未选 | 不在挖掘方块。 |
| Raycast | All；None / Enemy / All | 决定是否根据实际视线上的实体重定向攻击。None 使用原目标；Enemy 只考虑可攻击敌对目标；All 考虑所有射线上实体。**None 不等于关闭后续命中盒/距离检查。** |
| Criticals | Smart；Smart / Ignore / Always | Smart 按 Criticals 的等待策略择机攻击；Ignore 不等待暴击条件；Always 要求当前能形成暴击。与独立 Criticals 模块设置有联系。 |
| KeepSprint | 开 | 攻击时尽量保持疾跑；暴击策略要求停止疾跑时会让位。 |
| IgnoreOpenInventory | 开 | 背包/容器界面打开时仍允许相关逻辑运行。 |
| SimulateInventoryClosing | 开 | 在攻击准备/完成阶段模拟服务端关包/恢复背包状态；不是简单把本地 GUI 关掉。 |

### 3.2 Clicker：攻击时序

来源：[Clicker.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/Clicker.kt)、[ItemCooldown.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/ItemCooldown.kt)、[KillAuraClicker.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/KillAuraClicker.kt)。

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| CPS | 5–8；1–60 | 点击计划的频率区间。最终攻击还受武器冷却、格挡、距离等限制；不是保证的每秒命中次数。 |
| Technique | Stabilized | 如何在 tick 序列中分配点击，见下表。 |
| AttackCooldown | 开 | 考虑游戏挥空后 missTime 的攻击等待；和武器蓄力是两种冷却。 |
| ItemCooldown.Minimum | 1–1；0–2 | 武器攻击冷却进度阈值区间，每轮抽样。1 是完整冷却；大于 1 延后攻击。 |
| ItemCooldown.IgnoreOnShieldBreak | 开 | AutoWeapon 准备破盾且目标盾会挡住攻击时，允许略过该冷却门槛。 |
| ItemCooldown.IgnoreOnMaceSmash | 开 | AutoWeapon 准备重锤下砸时允许略过冷却门槛。 |
| ItemCooldown.IgnoreWhenExitingRange | 开 | 预测即将离开攻击范围时允许提前攻击；包含目标 hurtTime 和未来视线/距离条件。 |

Technique 全部选项：

| 选项 | 实际安排方式 |
| --- | --- |
| Stabilized | 尽量均匀分配点击及余数。 |
| Efficient | 在较高 CPS 时倾向隔 tick 分布；抽到低于 10 CPS 时回退 Stabilized。 |
| Spamming | 将点击随机放入周期中的 tick，允许同 tick 多次。 |
| DoubleClick | 每次向随机 tick 加两次点击；计划总数可达到所抽 CPS 的两倍。 |
| Drag | 把点击集中到约 17–18 tick 的活动段，余下部分留空。 |
| Butterfly | 随机分配单击/双击，形成簇。 |
| NormalDistribution | 根据内置的两组正态间隔分布生成点击。**本地实现没有使用传入的 CPS 区间，不能以 CPS 滑块推断它的实际频率。** |

LB 的 Clicker 会保存未来 tick 的点击计划，因此可以询问“再过 N tick 会不会攻击”。这是它把转向准备与攻击时机联动的基础。

### 3.3 Range：距离

来源：[KillAuraRange.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraRange.kt)、[RangeValueGroup.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/range/RangeValueGroup.kt)。

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| RangeIncrease | 1 格；0–5 | 在玩家基础实体交互距离上增加的值。也用于调整物品 AttackRange 的最大距离。**它是增量，不是我们 `range` 那样的绝对请求值。** |
| ThroughWallsRange | 3 格；0–8 | 遮挡目标允许的距离；设置变更时最多裁到普通交互距离。与“是否允许隔墙转头”分开。 |
| ScanRangeIncrease | 2–3 格；0–7 | 在 `max(普通距离, 隔墙距离)` 上再增加的扫描距离，成功攻击等更新点重新抽样。 |

`MinRangeDecrease` 在本地源码中被注释掉，**不是当前可配置参数**。旧 `Range/WallRange/ScanExtraRange` 出现在迁移代码里，不要当成另一组当前开关。

### 3.4 Target：选敌

来源：[TargetTracker.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/combat/TargetTracker.kt)、[KillAuraTargetTracker.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/KillAuraTargetTracker.kt)。

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| FOV | 180°；0–180° | 允许目标偏离当前视线的最大角度。**LB 的 180° 对应完整方向；我们的 180° 只允许偏离 90°。** |
| HurtTime | 10；0–10 tick | 目标受伤计时必须不大于此值，参与通用目标验证。 |
| Priority | 有序组合 Type → Health；至少一项 | 可以选多个排序键并指定优先顺序。源码声明默认值是 Type、Health；排序比较器另由变更回调更新。 |
| IgnoreShield | 开 | 开启时不因为目标举盾就排除它。关闭时会检查盾是否实际挡住攻击；持斧/计划破盾和旧协议有例外。 |

Priority 全部选项：

| 选项 | 含义 |
| --- | --- |
| Type | 玩家优先，再是敌对怪物、对自己生气的中立生物等。 |
| Health | 实际生命值更低的优先，使用公共实际血量计算。 |
| Distance | 到命中盒更近的优先。 |
| Direction | 更接近视线方向的优先。 |
| HurtTime | 受伤计时更低的优先。 |
| Age | 实体存在 tick 数更多的优先。 |

KillAura 使用公共 `shouldBeAttacked` 判断目标类别、队友等，没有在这个子树重复提供我们这种 `Target players / Other entities` 列表。那些全局规则要在对应公共设置/模块中看。

### 3.5 Rotations：转向总设置

来源：[KillAuraRotationsValueGroup.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/KillAuraRotationsValueGroup.kt)、[RotationsValueGroup.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/RotationsValueGroup.kt)。

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| AngleSmooth | Linear；Linear / Sigmoid / Interpolation / Acceleration / AI | 选择转向处理器，各自参数见后续小节。 |
| MovementCorrection | Silent；Off / Strict / Silent / ChangeLook | Off 不修正；Strict 按实际使用的朝向修正移动；Silent 再调整方向输入以尽量保持原走向；ChangeLook 修改玩家实际视角。 |
| ResetThreshold | 2°；1–180° | 回到相机方向时，允许释放转向控制的角度误差阈值。不是每 tick 速度。 |
| TicksUntilReset | 5 tick；1–30 | 转向请求不再续期后进入重置的保持时间；不是“用 5 tick 完成所有回正”的保证。 |
| RotationTiming | Normal；Normal / Snap / OnTick | Normal 持续转向；Snap 结合预计转向耗时和未来点击计划决定何时开始；OnTick 尽量在攻击 tick 准备朝向，必要时提前转，并在攻击准备/结束路径发临时朝向及恢复包。 |
| ThroughWalls | 关 | 允许可见选点失败时仍对遮挡后的目标求转向。**不自动放宽 Range.ThroughWallsRange 的攻击门槛。** |

#### 3.5.1 Linear

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| HorizontalTurnSpeed | 180–180°；0–180 | 每次转向处理的水平最大角度步长区间。 |
| VerticalTurnSpeed | 180–180°；0–180 | 每次转向处理的垂直最大角度步长区间。 |

来源：[LinearAngleSmooth.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/anglesmooth/impl/LinearAngleSmooth.kt)。名称叫 Smooth 不代表默认很慢：180° 的预算足以在一次处理里完成很多转向。

#### 3.5.2 Sigmoid

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| HorizontalTurnSpeed | 180–180°；0–180 | 水平基准步长预算。 |
| VerticalTurnSpeed | 180–180°；0–180 | 垂直基准步长预算。 |
| Steepness | 10；0–20 | S 形速度缩放曲线的陡峭程度，控制快慢过渡有多集中。 |
| Midpoint | 0.3；0–1 | S 形曲线相对角误差的转折位置，不是目标身体的瞄准高度。 |

来源：[SigmoidAngleSmooth.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/anglesmooth/impl/SigmoidAngleSmooth.kt)。

#### 3.5.3 Interpolation

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| HorizontalSpeed | 80–85%；1–100% | 水平剩余角度的追随系数，结合当前误差走不同曲线；不是固定 °/s。 |
| VerticalSpeed | 20–25%；1–100% | 垂直剩余角度的追随系数。 |
| DirectionChangeFactor | 95–100%；0–100% | 根据前后目标方向变化增加响应的权重。 |
| Midpoint | 0.35；0–1 | 归一化角误差分界；大误差走 Bézier 分支，小误差走 Sigmoid 分支。 |

来源：[InterpolationAngleSmooth.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/anglesmooth/impl/InterpolationAngleSmooth.kt)。

#### 3.5.4 Acceleration

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| YawAcceleration | 20–25；1–180 | 每次转向更新中，水平角度步长允许变化的基础区间，近似 °/tick²。 |
| PitchAcceleration | 20–25；1–180 | 垂直对应项。旧别名 `PitchAccelelation` 是拼写迁移。 |
| DynamicAccel.Enabled | 关 | 射线已经对准实体时，启用专门的交叉准星加速度区间。 |
| DynamicAccel.CoefDistance | -1.393；-2–2 | 根据距离平移加速度区间的系数；**当前计算路径在 DynamicAccel 关闭时也计算并使用距离项**，开关主要控制区间替换。 |
| DynamicAccel.YawCrosshairAccel | 17–20；1–180 | 动态模式且准星射线命中时的水平加速度区间。 |
| DynamicAccel.PitchCrosshairAccel | 17–20；1–180 | 对应垂直区间。 |
| AccelerationError.Enabled | 开 | 对本次算出的加速度添加比例随机误差。 |
| AccelerationError.YawAccelError | 0.1；0.01–1 | 水平加速度随机比例误差幅度。 |
| AccelerationError.PitchAccelError | 0.1；0.01–1 | 垂直加速度随机比例误差幅度。 |
| ConstantError.Enabled | 开 | 添加与当前加速度大小无关的随机角步长误差。 |
| ConstantError.YawConstantError | 0.1；0.01–1 | 水平固定误差幅度。 |
| ConstantError.PitchConstantError | 0.1；0.01–1 | 垂直固定误差幅度。 |
| SigmoidDeceleration.Enabled | 关 | 使用 S 形因子缩放加速度。 |
| SigmoidDeceleration.Steepness | 10；0–20 | 减速曲线陡峭程度。 |
| SigmoidDeceleration.Midpoint | 0.3；0–1 | 减速曲线的转折位置。 |

来源：[AccelerationAngleSmooth.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/anglesmooth/impl/AccelerationAngleSmooth.kt)。该实现使用上次旋转步长，但加入误差之后仍要看最终输出，不能仅凭名字认定拥有严格 jerk 上限。

#### 3.5.5 AI

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| Model | 已加载模型列表中的默认项 | 选择二维旋转回归模型。选项来自本机模型目录并动态更新，没有固定通用的模型名。 |
| OutputMultiplier.Yaw | 1.5；0.5–2 | 模型水平输出倍率。 |
| OutputMultiplier.Pitch | 1；0.5–2 | 模型垂直输出倍率。 |
| Correction | Interpolation；Interpolation / Linear / None | 对模型结果使用额外纠正处理器。 |
| Correction.Interpolation.HorizontalSpeed | 2–5%；1–100% | AI 纠正阶段水平追随系数；区别于普通 Interpolation 的 80–85%。 |
| Correction.Interpolation.VerticalSpeed | 2–5%；1–100% | AI 纠正阶段垂直追随系数。 |
| Correction.Interpolation.DirectionChangeFactor | 95–100%；0–100% | AI 纠正阶段的方向变化权重。 |
| Correction.Interpolation.Midpoint | 0.35；0–1 | 同普通 Interpolation 的分支边界。 |
| Correction.Linear.HorizontalTurnSpeed | 5–5°；0–180 | 线性纠正的水平步长预算。 |
| Correction.Linear.VerticalTurnSpeed | 5–5°；0–180 | 线性纠正的垂直步长预算。 |
| Correction.None | 无子参数 | 不进行该额外纠正。 |

来源：[AiAngleSmooth.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/anglesmooth/impl/AiAngleSmooth.kt)。AI 别名为 Minarai。它依赖可运行的模型环境/模型；异常或不支持路径会使用构造时提供的常规转向 fallback。不能把“AI”名称当成效果结论。

#### 3.5.6 Fail：主动制造转向偏差

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Enabled | 关 | 开启短暂的偏离目标处理。 |
| Rate | 3%；1–100% | 每游戏 tick 触发偏差状态的概率。 |
| Factor | 0.04；0.01–0.99 | 历史旋转差对附加偏移的权重；不是总平滑比例。 |
| StrengthHorizontal | 5–10°；1–90 | 水平随机偏移幅度，符号也会随机。 |
| StrengthVertical | 0–2°；0–90 | 垂直随机偏移幅度。 |
| TransitionInDuration | 1–4 tick；0–20 | 当前偏差状态持续时间。名称虽叫 Transition，当前处理直接组合偏移，不宜解释成严格连续的过渡曲线。 |

#### 3.5.7 ShortStop：短暂停顿

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Enabled | 关 | 开启短时降低转向步长的处理。 |
| Rate | 3%；1–25% | 在处理器运行时触发暂停的概率。 |
| Duration | 1–2 tick；1–5 | 暂停处理持续计数。暂停期仍允许很小的 0–0.1° 步进，并非严格完全静止。 |

来源：[FailRotationProcessor.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/FailRotationProcessor.kt)、[ShortStopRotationProcessor.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/ShortStopRotationProcessor.kt)。注册顺序是基础 AngleSmooth → Fail → ShortStop；后面的处理可能改变前面算出的运动性质。

### 3.6 AimPoint：落点

来源：[PointTracker.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/PointTracker.kt)。

基础流程是生成命中盒上的候选点、按排除条件筛选、选择靠近眼睛的点，再按 **Delay → Lazy → Gaussian** 顺序处理。

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| ExemptBoxParts | 空；Head / Body / Feet 多选 | 排除命中盒上、中、下三等分中的候选点。它是“排除部位”，不是“优先部位”。候选全部被排除时仍有最佳点 fallback，不是绝对禁打部位。 |
| ExemptBestHitVector.Enabled | 关 | 排除最靠近眼睛的最佳命中点周围区域。 |
| ExemptBestHitVector.Vertical | 0.2；0–1 | 最佳点附近的垂直排除容差。 |
| ExemptBestHitVector.Horizontal | 0.1；0–1 | 最佳点附近的水平排除容差。 |
| Lazy.Enabled | 关 | 新点距离旧点变化未达阈值时保持原点，减少小幅频繁变化。 |
| Lazy.Threshold | 0.1–0.2 格；0.01–0.4 | 落点更新的距离阈值区间。 |
| Delay.Enabled | 关 | 保留旧落点若干处理周期后再接受新点。 |
| Delay.Delay | 2–4；0–5，界面标 ticks | 延迟区间。**实现是在 process 调用时递减；当调用频率不等于每 tick 一次时，不应保证等于对应的游戏 tick 数。** |
| Gaussian.Enabled | 关 | 在落点上增加经过追随处理的高斯随机偏移。 |
| Gaussian.YawOffset | 0–0；0–1 | 水平 X/Z 偏移幅度系数，不是直接 yaw 度数。 |
| Gaussian.PitchOffset | 0–0；0–1 | 垂直 Y 偏移幅度系数，不是直接 pitch 度数。 |
| Gaussian.Chance | 100%；0–100% | 偏移接近目标后重新抽取偏移的概率。 |
| Gaussian.Speed | 0.1–0.2；0.01–1 | 当前偏移向随机目标偏移靠近的插值比例。越大变化越快。 |
| Gaussian.Tolerance | 0.05；0.01–0.1 | 认为已经接近目标偏移、可以重新取样的容差。 |
| Gaussian.Dynamic.Enabled | 关 | 设计上根据实体受伤计时和自身速度调整偏移，见下方实际路径限制。 |
| Gaussian.Dynamic.HurtTime | 10；0–10 | 设计触发条件为实体 hurtTime **大于等于**该值，方向与 Target.HurtTime 过滤相反。 |
| Gaussian.Dynamic.YawFactor | 0；0–10 倍 | 按玩家水平速度增加水平偏移幅度的倍率。 |
| Gaussian.Dynamic.PitchFactor | 0；0–10 倍 | 按玩家水平速度增加垂直偏移幅度的倍率。 |
| Gaussian.Dynamic.Speed | 0.5–0.75；0.01–1 | 动态条件满足时替代普通 Speed。 |
| Gaussian.Dynamic.Tolerance | 0.1；0.01–0.1 | 动态条件满足时替代普通 Tolerance。 |

本地快照的两处实现限制：

1. Gaussian 的 process 只有在 **YawOffset 与 PitchOffset 都抽到正值**时才更新偏移。默认均为 0，仅开启 Gaussian 不会自动产生抖动。
2. process 调用 `updateGaussianOffset(point)`，传入的是 PointInsideBox；而 Dynamic 检查 `entity is LivingEntity`。在这条 KillAura 处理路径上该条件不会满足，因而不能认为界面上的 Dynamic 参数目前能按设计触发。

核对来源：[PointProcessorGaussian.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/features/PointProcessorGaussian.kt)、[PointProcessorDelay.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/features/PointProcessorDelay.kt)、[PointProcessorLazy.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/features/PointProcessorLazy.kt)。这些也是“可以参考架构，但应核对每条实际调用链”的例子。

### 3.7 AutoBlocking：格挡流程

来源：[KillAuraAutoBlock.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraAutoBlock.kt)。

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| Enabled | 关 | 自动格挡子功能总开关。 |
| BlockMode | Interact；Basic / Interact / Fake | Basic 直接尝试使用格挡物品；Interact 先按面向的实体/方块进行交互尝试，再进入使用路径；Fake 只维持视觉表现。 |
| SimulateVanillaUse | 开 | 模拟原版主手/副手尝试使用的顺序。 |
| UnblockMode | StopUsingItem；StopUsingItem / ChangeSlot / SwapHand / None | 解除格挡方式：停止使用、切槽、交换手、或不主动解除。None 不保证游戏本身允许一边使用一边攻击。 |
| Reblock | 0–0 tick；0–3 | 攻击后重新格挡前的等待。0 对应立即重新格挡。旧别名 TickOn。 |
| PauseOnUnblock | 0–0 tick；0–3 | 解除格挡后暂停攻击的时间。旧别名 TickOff。 |
| Chance | 100%；0–100% | 启动格挡尝试的概率。 |
| Blink | 0 tick；0–10 | 格挡相关发包缓存/释放的时间控制；0 不形成持续的等待窗口。 |
| PrioritizeBlocking | 开 | 没有完成应有格挡且满足危险条件时优先格挡，可能推迟攻击。 |
| OnScanRange | 开 | 目标仅在扫描范围、尚不满足攻击范围时也允许尝试格挡。 |
| OnlyWhenInDanger.Enabled | 关 | 只在目标射线等条件显示自己有受击危险时格挡。 |
| OnlyWhenInDanger.Tolerance | 0.3 格；0–1 | 危险判定时扩大自己的命中盒，越大越容易认为对方正在对准自己。 |
| OnlyWhenInDanger.ForceActiveRange | 0–1 格；0–6 | 危险射线命中点处于此距离区间时，可直接满足距离/视线部分条件；仍要求前面的射线命中判定成立。 |
| AssumeShield | 关 | 为特定旧协议/跨版本格挡场景假定可用盾/格挡手；不是给物品直接添加防御能力。 |

Reblock/PauseOnUnblock 会在对应流程节点重新抽样。`blockVisual`、`reblockTicks`、`waitTicks` 等运行时字段不是另一些 UI 参数。

### 3.8 FailSwing：挥空与反馈

来源：[KillAuraFailSwing.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraFailSwing.kt)、[KillAuraNotifyWhenFail.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraNotifyWhenFail.kt)。

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| Enabled | 关 | 在附近有目标、当前未能正常攻击等条件下，允许空挥动作。不是穿透距离伤害。 |
| AdditionalRange | 2.5–3 格；0–10 | 判定是否值得空挥时，在普通距离之外增加的范围。 |
| NotifyWhenFail | Box；None / Box / Sound | 空挥失败反馈方式。 |
| NotifyWhenFail.Box.Fade | 4；1–10，界面标秒 | 失败落点方框的淡出时长。**当前源码按渲染调用计数并使用 50 × Fade 的阈值，不是可靠的真实秒计时。** |
| NotifyWhenFail.Box.Color | RGBA(255,179,72,255) | 失败落点方框颜色。 |
| NotifyWhenFail.Box.Rainbow | 关 | 用彩虹色代替固定颜色。 |
| NotifyWhenFail.Sound.Volume | 50；0–100 | 反馈音量百分比。 |
| NotifyWhenFail.Sound.Pitch | 0.8；0–2 | 反馈声音音高。 |

FailSwing 是“未击中时挥手”的功能；Rotations.Fail 是“主动让朝向产生偏差”的处理器，两者不同。

### 3.9 FightBot：自动走位

来源：[KillAuraFightBot.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraFightBot.kt)、[NavigationBaseValueGroup.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/navigation/NavigationBaseValueGroup.kt)。

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| Enabled | 关 | 自动接近、避让、导航辅助总开关。 |
| Auto | Jump、Swim、Sprint 全选 | 导航过程中允许的自动动作：障碍跳跃、水中上浮/游动辅助、疾跑。 |
| OpponentRange | 3 格；0.1–10 | 假定对手能威胁自己的距离，用于走位危险判断。不是自己的攻击距离。 |
| DangerousYaw | 55°；0–90 | 对手朝向与指向自己所需朝向的角差阈值，用于评估是否正面受威胁。 |
| RunawayOnCooldown | 开 | 自己的点击计划尚未到攻击时机时，允许拉开距离。 |
| TargetFilter.Range | 50 格；10–100 | 为导航挑选远距离目标的搜索上限。不是 50 格攻击。 |
| TargetFilter.VisibleOnly | 开 | 导航目标需通过可见性检查。 |
| TargetFilter.NotWhenVoid | 开 | 排除下方缺少支撑、处于虚空风险中的目标。 |
| Leader.Enabled | 关 | 开启指定玩家跟随逻辑。 |
| Leader.Username | 空 | 跟随的玩家名。 |
| Leader.Radius | 5 格；2–10 | 跟随时希望保持的半径。 |

### 3.10 RangeIndicator：范围显示

这一组只控制视觉显示，不改变判定距离。颜色采用 RGBA，第四项是透明度，0 表示透明。

来源：[KillAuraRangeIndicator.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraRangeIndicator.kt)。

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| Enabled | 关 | 显示范围指示。 |
| ColorMode | Static；Static / Rainbow / Distance | 固定状态颜色、彩虹色、按目标距离着色。 |
| IdleColor | (255,50,50,80) | 空闲颜色。 |
| ActiveColor | (50,255,50,80) | 有目标时颜色。 |
| Outline | 开 | 绘制轮廓。 |
| OutlineColor | (255,255,255,120) | 轮廓颜色。 |
| PulseAnimation | 关 | 开启范围圈的呼吸/脉冲。 |
| PulseSpeed | 2；0.5–5 | 脉冲速度。 |
| PulseIntensity | 0.15；0.05–0.5 | 脉冲幅度。 |
| FadeAnimation | 开 | 状态变化时颜色渐变。 |
| FadeSpeed | 0.1；0.01–0.5 | 渐变速度，越大越快。 |
| WallRangeColor | (255,165,0,0) | 隔墙范围颜色，默认透明。 |
| ScanRangeColor | (100,100,255,0) | 扫描范围颜色，默认透明。 |
| OpponentRangeColor | (255,0,0,0) | 对手范围颜色，默认透明。 |
| HideWhenDead | 开 | 自己死亡时隐藏。 |
| HideWhenSpectator | 开 | 旁观者状态隐藏。 |
| HideInVehicle | 关 | 乘坐载具时隐藏。 |
| RespectInventorySetting | 开 | 按 KillAura 的背包处理设置决定是否在打开背包时显示。 |
| CanBeCovered | 关 | 是否允许场景遮挡范围图形，即控制深度遮挡表现。 |

### 3.11 TargetRendering：目标标记

来源：[TargetRenderer.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/render/TargetRenderer.kt)。

| 参数 | 默认值 / 可选值 | 含义 |
| --- | --- | --- |
| Enabled | 开 | 显示当前目标标记。 |
| Mode | GlowingCircle | 可选 Legacy、Circle、Image、GlowingCircle、Ghost、Hearts、Text2D、Arrow。每种子参数如下。 |

本节的“主题蓝”指源码 `Color4b.LIQUID_BOUNCE`；默认半透明主题色为该颜色、Alpha 100。所有尺寸均为绘制参数，不改变真实命中盒。

#### Legacy

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Size | 0.5；0.1–2 | 目标顶部小方框的水平半尺寸。 |
| Height | 0.1；0.02–2 | 方框高度。 |
| Color | 主题蓝，Alpha 100 | 方框颜色。 |
| ExtraYOffset | 0.1；0–1 | 在实体顶部再增加的高度。 |

#### Circle

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Radius | 0.85；0.1–2 | 外圆半径。 |
| InnerRadius | 0；0–2，并裁到外半径以内 | 内圆半径，控制环形的内侧。 |
| HeightMode | Feet | 圆环高度策略，全部子参数见 3.12。 |
| OuterColor | 主题蓝，Alpha 100 | 外侧颜色。 |
| InnerColor | 主题蓝，Alpha 100 | 内侧颜色。 |
| Color | #007CFF，不透明 | 圆环轮廓颜色；名称不是 OutlineColor。 |

#### GlowingCircle

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Radius | 0.85；0.1–2 | 发光圆半径。 |
| HeightMode | Feet | 高度策略。 |
| OuterColor | 主题蓝，Alpha 100 | 圆的主体颜色。 |
| GlowColor | 主题蓝，Alpha 0 | 发光梯度另一端颜色。 |
| GlowHeight | 0.3；-1–1 | 发光梯度高度差；Animated 高度模式另有随时间变化的高度差。 |
| Color | #007CFF，不透明 | 轮廓颜色。 |

#### Ghost

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Color | 蓝色 | 环绕光点/拖尾颜色。 |
| Size | 0.5；0.4–0.7 | 光点尺寸。 |
| Length | 25；15–40 | 拖尾采样段数，影响长度与绘制数量。 |

#### Hearts

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Color | 白色，Alpha 180 | 普通生命爱心颜色，吸收血量另用金色。 |
| DynamicCount | 开 | 按目标血量决定爱心数量。 |
| HeartCount | 10；1–32 | DynamicCount 关闭时的普通爱心数量。 |
| YOffset | 0.1；-1–3 | 爱心分布的基础高度偏移。 |
| Size | 0.15；0.05–1 | 单个爱心大小。 |
| Orbit.Radius | 0.5；0.1–1 | 环绕半径。 |
| Orbit.Speed | 35°/s；-360–360 | 环绕速度，正负改变方向。 |
| Orbit.SqueezeStrength | 0.25；0–1 | 受伤反馈时轨道收缩幅度。 |
| Orbit.SqueezeSpeed | 2；1–4 | 控制受伤计时中收缩/恢复的阶段区间，不是直接 °/s。 |
| CanBeCovered | 关 | 是否允许爱心被场景遮挡。 |

#### Text2D

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Scale | 1；0.01–10 | 文字缩放。 |
| Shadow | 开 | 文字阴影。 |
| Color | 红色 | 文字颜色。 |
| Text | 列表 ["TARGET"] | 显示的文字行。 |
| HeightMode | Feet | 目标上用于投影文字的位置高度策略。 |

#### Arrow

| 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Color | 红色 | 指向目标顶部的箭头颜色。 |
| OutlineColor | 透明 | 箭头轮廓颜色。 |
| Size | 1.5；0.5–20 | 箭头缩放。 |

#### Image

| 参数 | 默认值；范围/选项 | 含义 |
| --- | --- | --- |
| Source | Custom；Custom / Builtin | 自定义 PNG 或内置标记图。 |
| Source.Custom.File | 未选择；PNG 文件 | 自定义图片位置。 |
| Source.Builtin.Preset | Marker1；Marker1 / Marker2 | 内置标记样式。 |
| Scale | (1,1) | 横向、纵向缩放向量。 |
| ColorModulator | 白色 | 图片乘色/透明度调制。 |
| Rotate.Curve | (0,0) → (1,0)；进度 0–1，角度 -180–180° | 图片旋转动画曲线；默认始终 0°。 |
| Rotate.Period | 1000 ms；10–20000 | 曲线的一次推进周期。 |
| Rotate.Symmetric | 开 | 进度来回往返；开启时完整往返需要两倍 Period。 |
| HeightMode | Feet | 图片锚点高度策略。 |

Image 的继承参数来源：[TextureMode.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/config/utils/TextureMode.kt)、[AnimatedValueGroup.kt](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/render/utils/AnimatedValueGroup.kt)。

### 3.12 TargetRendering 共用 HeightMode

Image、Circle、GlowingCircle、Text2D 各有独立的一份高度模式设置，默认 Feet。

| 模式 / 参数 | 默认值；范围 | 含义 |
| --- | --- | --- |
| Feet.Offset | 0；-1–1 | 相对实体脚底的偏移。 |
| Top.Offset | 0；-1–1 | 相对实体顶部的偏移。 |
| Relative.Height | 0.5；-0.5–1.5 | 实体高度比例；0 是脚底，1 是顶部，0.5 是中间。 |
| Health | 无子参数 | 按当前血量/最大血量比例决定显示高度。 |
| Animated.Speed | 0.18；0.01–1 | 随实体 tick 和插值时间推进正弦动画的速度系数。 |
| Animated.HeightMultiplier | 0.4；0.1–1 | 正弦高度振幅。 |
| Animated.HeightOffset | 1.3；0–2 | 正弦高度中心偏移。 |
| Animated.GlowOffset | -1；-3.1–3.1 | 发光端相位偏移；不是固定世界高度差。 |

### 3.13 LB 参数中容易照搬出错的关系

- `RangeIncrease` 是增量，我们 `range` 是被玩家属性截断的绝对值。
- LB FOV 是偏离角上限，我们 FOV 是总角度。
- `Rotations.ThroughWalls` 控制能否隔墙求朝向，`Range.ThroughWallsRange` 控制遮挡攻击距离。
- `Acceleration` 管的是角度步长变化；我们 `motion.maxAcceleration` 管的是目标移动预测，两者单位和对象都不同。
- `Fail`、`ShortStop`、`Gaussian`、`FailSwing` 分别作用在转向偏差、转向暂停、落点和挥手事件，不能统称一个“抖动值”。
- 本地 LB 也存在参数名、计数单位和实际调用路径不完全对应的情况。应参考经过核对的职责划分，不能预设所有分支已经正确。

<a id="refactor-plan"></a>

## 4. 两边差异与建议重构方案

以下是全面重构的设计目标。已实施部分目前仅为第 8 节的 AimPoint 后处理；统一最终转向控制器、攻击流程重构和参数全面迁移仍未实施。

### 4.1 我们已经具备的基础

现有 SilentAura 并非一个完全没有结构的实现，已经有：

- 独立的配置、目标、转向、攻击、格挡文件；
- 完整命中盒可见表面 fallback；
- 目标局部锚点、预测器和部分射线保留策略；
- RotationLease 仲裁、每 tick 候选缓存、最终发送确认；
- Latest/Legacy 分开的攻击调度；
- 最终攻击距离和射线验证。

这些应保留和整理。重构目标是减少职责交叉与参数耦合，并让约束能被测量。

### 4.2 最值得参考 LB 的部分

| 方向 | LB 提供的参考 | 我们的具体改进 |
| --- | --- | --- |
| 选敌与排名 | TargetTracker / 有序 Priority | 让排序成为独立策略，区分目标是否可追踪、是否可攻击、是否应该切换。 |
| 落点管线 | PointTracker / 排除、延迟、惰性、随机偏移 | 独立维护目标局部落点；每个处理器明确输入输出、生命周期、何时重新做可见性检查。 |
| 可替换转向器 | AngleSmooth 的多个实现 | 把 Lock/Balance/FullLock 的角色说清楚，速度、加速度、响应与预测分别配置。 |
| 准备与执行时机 | Clicker.willClickAt / RotationTiming | 调度器能提供未来攻击窗口，供转向评估；最终是否出手由同一份有效角度与命中判定决定。 |
| 距离/遮挡语义 | 普通距离、隔墙距离、扫描增量、隔墙转向分开 | 消除一个 Through blocks 同时控制追踪和攻击的歧义。 |
| 调试 | 角度、落点、目标、攻击条件分别可观察 | 增加最终角度轨迹及差分统计，而不是只看头部动画是否顺。 |

暂不把 AI、主动 Fail、ShortStop、自动走位列为核心重构前提。这些功能不能代替时间基准和轨迹正确性。

### 4.3 建议的数据流

`输入/状态 → 候选目标 → 合格落点 → 转向轨迹 → 本 tick 提交角度 → 最终命中检查 → 攻击调度 → 发送确认反馈`

| 组件职责 | 输入 / 输出 | 要解决的问题 |
| --- | --- | --- |
| TargetSelector | 候选实体、FOV、距离、排序策略 → 目标候选快照 | 全量扫描有明确 tick 缓存，包括“无目标”；避免每个渲染帧重复扫世界。 |
| PointTracker | 目标当前命中盒、视线、局部锚点 → 可用落点 | 保留有意义的目标局部状态，避免旧世界坐标失效；遮挡时回退。 |
| RotationPlanner | 落点、眼睛、已确认角度/速度 → 本 tick 轨迹 | 集中负责响应、速度、加速度和需要的 jerk 预算，减少串联多个隐式追踪器。 |
| AttackScheduler | 武器状态、CPS/蓄力、Critical、格挡 → 最早可攻击窗口 | 不参与落点随机化，不自行生成另一套攻击朝向。 |
| AttackValidator | 同一提交角度、当前实体/世界 → 命中/失败原因 | 出手前重新检查目标身份、配置过滤、hurtTime、距离、遮挡和实体拦截。 |
| RotationLifecycle | 获得控制、换目标、外部抢占、丢目标、关闭 → 状态转移 | 回正和中断有明确规则，不恢复上次模式留下的历史。 |

若采用 tick 主导的转向轨迹，渲染层可以对轨迹插值。也可以保留连续时间规划，但必须保证不同 FPS 输入同一运动时，最终 tick 输出在容差内一致。**不应先认定“多加一层 smooth”就是解决办法。**

### 4.4 参数整理方案

基础界面建议只展示：

- 激活条件；
- 攻击/扫描范围；
- FOV、目标类别和优先级；
- 目标保持/切换方式；
- 转向算法、跟随响应；
- CPS 或蓄力；
- 格挡、Critical 和回正。

高级分组再展示：

- Yaw/Pitch 速度与加速度预算；
- 若实现了 jerk 约束，明确其单位与作用位置；
- 落点策略、漂移幅度与更新时间；
- 目标运动预测参数；
- 遮挡追踪与遮挡攻击；
- 调试采样和统计。

需要改名或改语义的重点：

| 现有项 | 建议 |
| --- | --- |
| Smooth | 改成 Tracking response，明确越大越快；或改成响应时间，明确越大越慢，两者择一。 |
| Return smooth | 改成 Return response / Return duration，不能用一个名字混合速度和时间。 |
| Turn prediction | 改成 Prediction strength，并说明前馈/位置提前的作用范围。 |
| Optimize（原 Limitation） | 用户确认保留并优化此入口，明确显示策略与有效预算。本轮先完成命名与组件实验；参考 Legitimize 的历史步长过渡作为可辨认策略、暴露有效预算等工作仍待后续实现。 |
| Full-lock smoothing | 如保留现有算法，改叫 Step scale；若继续叫 Smoothing，应实现真正的轨迹连续性。 |
| Aim point.Center | 名称/说明明确“跟随眼高的上半身落点”，或新增真正几何中心模式。 |
| 隐藏的 FullLock 预测参数 | 要么专用固定配置，要么显式展示继承关系；不让看不见的旧值继续改变效果。 |

旧配置键应做迁移/兼容，而不是直接删除，使已有配置在升级后可解释。

### 4.5 优先顺序

**第一步：让行为和参数一致。**

- 修复 hurtTime 在锁定/攻击路径上的不一致。
- 缓存无目标结果，限定扫描频率。
- 明确实际生效距离与配置距离。
- 澄清 Smooth、FullLock Smoothing、预测等名称和隐藏依赖。

**第二步：统一最终转向轨迹。**

- 以最新确认的服务端角度为连续性依据。
- 明确帧插值、tick 规划、包量化各自的职责。
- 把速度/加速度/jerk 的预算定义到正确阶段，并检查量化后的边界。
- 将换目标、交叉目标、遮挡恢复、回正纳入同一生命周期验证。

**第三步：接入独立选点和可配置排序。**

- 排序不依赖旋转控制器的随机状态。
- 选点有明确可见性与范围约束。
- 把未来攻击窗口提供给转向规划；实际攻击仍要重新验证。

**第四步：用轨迹数据评估。**

- 同输入、同目标运动，比较不同参数及不同 FPS。
- 同时报告跟踪误差、命中情况、等待时间、最终 v/a/j 与重复模式。
- 不以“动作看上去慢”“加了随机数”或“某一次没报错”代替验证。

我们的客户端攻击/移动包顺序已有专门的 pre-movement 约束。LB OnTick 会显式插入朝向/恢复包，不能在没有验证版本协议与现有 RotationLease 交互的情况下原样照搬。

<a id="balance-redesign"></a>

### 4.6 步长、Balance 拖延和特征量：这次应怎样一起解决

**先定单位。**以每个逻辑 tick 一次更新为例，角度为 `θ[k]`：

```text
角度步长 v[k] = wrap(θ[k] - θ[k-1])             单位：°/tick
步长变化 a[k] = v[k] - v[k-1]                   单位：°/tick²
加速度变化 j[k] = a[k] - a[k-1]                 单位：°/tick³
```

Yaw 差使用最短角差；Pitch 差直接相减。记录还应保留原始数值差，用来发现等价角之间意外跳了 360°。不同时间间隔的数据不能直接套等间隔公式。

| 概念 | 直观例子 | 能控制什么 |
| --- | --- | --- |
| 最大步长 | 相差 100°，本 tick 最多转 30° | 限制一次转多少；不规定上一个 tick 和下一个 tick 转多少。 |
| 最大加速度 | 上次转 5°，本次最多增加到 8° | 限制步长怎样变快、变慢。 |
| 最大 jerk | 上次加速度为 2，本次只能在其附近变化 | 限制加速和刹车的切换有多突然。 |
| 跟随响应 | 面对相同误差，有多积极地追上目标 | 影响收敛时间和移动目标的滞后；仍受上述运动预算限制。 |
| 预测/前馈 | 根据目标已观察到的运动，预先给出一部分跟随速度或未来落点 | 减少追着旧位置跑；不能替代范围与射线判断。 |

现有 FullLock 的实现是：`实际步长 = clamp(角度误差, ±AngleStep) × (1 - 0.5 × Smoothing)`，另有小角死区与最终量化。例如误差 100°、步长 90°、Smoothing=1，本次转 45°；下一次若误差只剩 5°，就转 2.5°。它只缩放本次结果，**没有根据上一步构造连续的加速/刹车过程**。

现有 Balance 的拖延来自多个层次共同追赶：帧级指数响应、帧级速度惯性、包级加速度处理。包级拿到的目标角度已经滞后，随后再追一次。单独调快 Smooth 只能改变其中一层；单独压低步长又会拉长到达时间。具体实现见 [SmoothA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothA.java)、[AimStepA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimStepA.java)、[PacketRotationSmoother](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/PacketRotationSmoother.java)。

建议确定以下设计，而不是继续叠加追踪器：

1. **一个最终控制器。**直接从当前可用落点求目标角，结合已确认输出的速度/加速度规划下一步；渲染只显示这一条轨迹。
2. **大误差及时加速，小误差提前刹车。**跟随响应负责“想转多快”，v/a/j 预算负责“本次允许怎么转”；需要考虑后续刹停距离，不能最后用 `min(误差, 步长)` 硬截断来假装平滑。
3. **Balance 优先稳定可用落点。**射线已经穿过可用命中区域时，不要只因锚点刷新而追向另一个点；当前点被遮挡或离开合法区域时才纠正。稳定点的策略也应服务 Lock，避免两套几何规则不断分叉。
4. **预测只有一个明确入口。**先选目标运动预测或角速度前馈的组合方式，明确各自承担哪段延迟；避免位置提前和角速度补偿重复计算同一段运动。反向、瞬移、重生时使过期预测失效。
5. **所有偏移都要经过最终约束。**落点漂移先验证几何可用性，再生成角度；如果以后增加主动偏离，也必须进入控制器，不能在最终输出后直接加角度。

模式建议保留熟悉的名称，但共享状态、几何规则和最终约束：Lock 使用较积极的跟随预设；Balance 使用较稳定的落点与平缓的加减速预设；FullLock 使用更直接的目标角和较大的运动预算。具体默认值要由同一组轨迹回放确定，本文不把未经测试的数字当作新默认值。

**评价必须同时看“追上得多快”和“输出怎样变化”。**速度降低，可能伴随更长的恒速平台、更固定的收敛形状，或突然的末端刹停。较低 v/a/j 也不自动代表更接近真实鼠标轨迹，更不等于 Polar 等检测系统给出较低评分。本文能设计和测量的是可定义的运动量；检测器内部特征和效果仍未知。

<a id="implementation-contract"></a>

### 4.7 全面重构的执行接口与时间顺序

建议落到下面这些接口职责，名称可随工程规范调整。核心不是增加文件数量，而是消除同一状态被不同阶段反复推进。

```text
物理输入与世界状态
        ↓
本次玩家更新：选择目标 → 更新局部落点/预测 → 规划候选角
        ↓                                      ↓
攻击时间窗口与格挡状态 ────────────────→ 提交唯一角度
                                               ↓
                                    用提交角度重新验证命中
                                               ↓
                                    原有动作/移动发送顺序
                                               ↓
                                        实际发送观察
                                               ↓
                                    更新输出历史与只读统计

渲染：只读取快照、插值显示，不选敌、不规划、不提交角度。
```

**事件位置建议：**在 `PLAYER_UPDATE` 的较高优先级阶段完成准备，位于攻击与格挡消费朝向之前。现有适配器在这个事件之前调用 `RotationLease.finishMotion()`，适合作为一次新准备的入口；最终位置包阶段另有 `beginMotion()/finishMotion()`。实施前应逐一核对各版本实际调用次数与取消路径。来源：[RuntimeEventAdapter](E:/McEnv/moons/src/minecraft/features/java/com/blanoir/moons/features/RuntimeEventAdapter.java)、[RotationLease](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/management/lease/RotationLease.java)。

| 阶段 | 必须建立的约定 |
| --- | --- |
| 准备 | 一次玩家更新只推进一次目标、选点、预测与候选规划。缓存“无候选”结果。 |
| 快照 | 保存世界/玩家会话身份、目标对象身份、准备序号、来源眼睛位置、目标角、候选角和预算。单靠 entity ID 或 `player.tickCount` 不能覆盖所有生命周期。 |
| 提交 | 同一个动作窗口内的射线、移动修正和发包复用同一提交角度。`plan()` 与 `commit()` 分离；提交后不能因渲染、重复 getter 或重新排名改变角度。 |
| 执行 | 实体身份、最新位置、可攻击性、范围、遮挡、hurtTime 等由攻击验证器复查；若失效，等待后续准备，不临时生成另一套朝向。 |
| 发送观察 | 只在真正观察到自身控制的发送结果后更新已发送历史。这个“确认”是本地发送观察，不是服务器处理成功或伤害回执。 |
| 重复/取消 | 同一窗口重复读取必须幂等。取消的包不能当成已发送；额外包、无旋转字段的包和长间隔要有单独记录规则，不能都按一个普通 tick 推进历史。 |
| 渲染/HUD | 只读 `DebugSnapshot`。当前 HUD 调用 `attackRotation()`，而它可能提交角度；该副作用应消除。 |

当前调试路径来源：[SilentAura](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/SilentAura.java)、[SilentAuraRuntime](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraRuntime.java)。

不要假定 `RotationLease.window()` 等于游戏 tick：它在清理动作窗口时推进，同一次玩家更新可涉及多个生命周期节点。建议单独维护准备序号，提交再绑定实际的租约会话与动作窗口。

### 4.8 选敌、选点、攻击和生命周期也要一起整理

| 范围 | 具体修改方案 | 保留的边界 |
| --- | --- | --- |
| 选敌 | 独立配置优先级；区分可追踪、可命中、可立即攻击。Single 保持当前合格目标；Switch 在成功出手后的明确节点重新排名；不保证一定换到不同实体。 | 目标类别、AntiBot/队友等统一过滤继续通过 Targeting。缓存失效原因明确。 |
| hurtTime | 新目标筛选和出手验证使用同一阈值。当前目标短暂 hurtTime 过高时可继续追踪，但暂缓攻击。 | 不因刚打中对方就清空预测和选点历史。 |
| 身份 | 用世界会话与实体实例识别生命周期，ID 仅用于查找。 | 防止换世界或实体 ID 复用后继承旧状态。 |
| 选点 | 一个目标局部锚点；移动/姿态变化后投影到当前命中盒。漂移、预测处理后再次检查范围、遮挡与点是否可用，失败则求可见表面 fallback。 | 扫描范围内可准备朝向；只有实际攻击范围内的最终射线才能出手。 |
| 实体拦截 | 按实际交互语义识别挡在前面的可命中实体；拦截判断与“是不是允许攻击的敌人”分开。 | 不允许因为前方实体不属于敌人而把射线当成穿过去。不要只按 LivingEntity 简化所有交互物体。 |
| Latest 攻击 | 统一快照验证后再评估蓄力、Critical、物品使用状态。延期动作绑定原目标和租约，执行前复查。 | 蓄力阈值、原版蓄力计时、空挥冷却分别记录，不能混为一个 cooldown。 |
| Legacy 攻击 | 同一套几何验证接入 CPS 调度与解除格挡/攻击/重新格挡流程。 | CPS 计划不修改朝向；未真正执行的点击不记为成功攻击。 |
| 格挡 | 明确 Idle、Blocking、Releasing、Ready、Reblocking 的转移和取消条件，复用现有使用动作仲裁。 | Latest 视觉格挡和 Legacy 真实使用能力分别呈现，不把动画状态当成协议状态。 |
| 暂停/抢占 | 统一释放物理输入代管、过期 Critical、格挡与旧候选；重新获取控制时从当前可靠朝向开始。 | 尊重其他模块和手动使用动作已持有的旋转租约。 |
| 模式切换 | 一个活动会话持有目标、落点和轨迹状态，模式只提供策略与预算。切换时明确重置预测或连续重规划。 | 消除六组目标历史和三组运动历史的隐含切换；不要在回到旧模式时恢复陈旧速度。 |
| 回正 | 用同一个规划器向当前镜头朝向回正，真正发送到位后释放；保持 yaw 数值连续。 | 目标消失、模块关闭、外部抢占有不同退出规则；抢占后不继续争夺控制权强行回正。 |

现有入口：[SilentAuraTargets](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraTargets.java)、[SilentAuraAttackRay](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraAttackRay.java)、[SilentAuraCombat](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraCombat.java)、[SilentAuraModes](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraModes.java)。

最终轨迹还需单独处理两个边界：

- **量化。**鼠标灵敏度决定可表达的最小角度步长。若预算小于一个量化单位，就可能找不到同时满足所有差分预算的下一步。要在求解阶段考虑量化网格和后续可行性，记录退化原因，不能先声称严格约束、最后又随意四舍五入。
- **极限与突变。**Pitch 的 ±90°边界、目标瞬移、外部重设朝向可能使所有连续性预算无法同时满足。保持角度有效与正确仲裁，显式重建历史并标记轨迹断点；统计报告必须单列这些事件。

### 4.9 参数迁移：每个旧设置有去向

以下是建议迁移规则，尚未新增配置键或写入用户配置。

| 旧参数组 | 新界面/模型中的去向 |
| --- | --- |
| enabled、combatMode、minCps/maxCps、minCharge/maxCharge、critical | 保留功能；按 Legacy/Latest 显示适用项；明确“调度阈值”与“游戏实际计时”。 |
| block、block.visual、block.range | 保留意图，分别显示自动使用、视觉效果和触发距离；解释模式/版本能力。 |
| range、scanExtra、throughBlocks | 普通攻击距离、扫描增量、允许遮挡追踪、遮挡攻击距离分开。旧 throughBlocks 的两个作用显式映射，迁移页面显示最终有效距离。 |
| fov、targetMode、hurtTime、target.player、target.entities | 保留；修复执行一致性，增加有序优先级。FOV 继续明确是总视野角，避免与 LB 半角语义混用。 |
| aimMode、smooth、matrix | aimMode 选择共享控制器的预设；smooth 迁移到有单位的跟随响应；matrix 的实际限制转换为可见预算预设。所有预设显示最终有效值。 |
| fullLock.angleStep、fullLock.smoothing | 旧步长乘缩放作为速度上限迁移的起点，再分别配置加速度与 jerk。只能近似保留速度偏好，不能声称等价复刻旧轨迹。 |
| returnRotation、returnSmooth | 回正开关与回正响应；沿用统一运动约束，不另外叠加随机回正追踪器。 |
| aimPoint、aimWander、aimWanderTicks | 明确部位策略、局部漂移幅度、采样间隔和保持规则；使用逻辑 tick 更新。 |
| jitter、jitterSpeed、settledJitter | 旧的路径/速度扰动应退役或迁入明确的局部落点偏移。新旧对象和单位不同，不能把旧浮点数原样解释成新的角度噪声；迁移说明需标注行为变化。 |
| predictionLead、predictionStrength、fullLock.prediction | 合并成可解释的提前量/前馈强度，说明是否允许同时启用与如何避免重复补偿。FullLock 使用可见的独立值或明确继承。 |
| motion.maxSpeed/maxAcceleration/maxHorizon/verticalScale/maxTurnRate/maxTurnAngle/minResponse/maxResponse | 保留为目标运动估计的高级组；始终标明格/tick等单位。它们不承担玩家角速度/角加速度约束。 |
| debugger | 变为只读调试快照、轨迹导出和统计开关。读数据不得改变规划、提交和点击行为。 |

新增的转向预算建议统一使用 `Yaw/Pitch MaxSpeed (°/tick)`、`MaxAcceleration (°/tick²)`、`MaxJerk (°/tick³)`；响应时间用 tick 并注明越小越快。UI 可同时显示 20 TPS 下的毫秒换算，卡顿时不承诺实际墙钟时间不变。

迁移需有配置版本号；只执行一次，保留原键值用于回查，新键已存在时以新键为准。无法一对一转换的旧项明确列出采用的预设和原因。所有默认值在回放验证后再定稿。

#### 4.9.1 FullLock 明确暴露哪些参数

按用户要求，FullLock 应提供直接可调的参数。建议普通页面先保留下面六项，其余放入可展开的高级组。这些是拟定的新界面，不是已经实现的设置。

| 普通项 | 单位/语义 | 目的 |
| --- | --- | --- |
| Horizontal speed | °/tick，水平最大步长 | 单独调转身速度，不与垂直方向共用一个 AngleStep。 |
| Vertical speed | °/tick，垂直最大步长 | 单独调抬头/低头速度。 |
| Tracking response | tick，响应时间，越小越积极 | 控制追踪误差的消除速度，避免旧 Smoothing 只缩放步长却名称模糊。 |
| Prediction lead | tick，0 表示不提前 | 控制预测目标位置的提前量。 |
| Aim point | Center / Closest | 按用户最新要求保留两个已有基础策略及其几何含义；若后续 FullLock 开放基础点选择，沿用这两个选项。 |
| Return response | tick，回正开启时显示 | 控制松开/失去目标后的回正响应。 |

高级组暴露 Yaw/Pitch 加速度和 jerk 预算、落点漂移幅度与保持间隔、目标预测的速度/加速度上限和垂直权重。高级组折叠时，普通页面仍显示当前有效的转向预算摘要，避免隐藏状态左右效果。

不新增含义笼统的 `Legitimize` 开关来同时控制惯性、随机化、死区。需要哪种行为，就调对应项；FullLock 继续共用最终运动约束。旧 AngleStep/Smoothing/Prediction 按 4.9 的规则迁移，具体新默认值经过回放再定。

### 4.10 可验收的实施顺序

| 阶段 | 交付 | 必须验证 |
| --- | --- | --- |
| A：建立基线 | 最终已发送角度的只读采样；固定输入轨迹回放；现有 Balance/Lock/FullLock 报告。 | 开关 HUD 不改变输出；区别准备、提交、实际发送、攻击尝试与服务器伤害反馈。 |
| B：统一时间与状态 | 一次准备、一个活动会话、提交缓存；修复空结果重复扫描和目标身份问题。 | 同输入在 30/60/144/240 FPS 下生成相同逻辑输出；重复查询、取消发送、外部抢占、重生不会推进虚构历史。 |
| C：新轨迹控制器 | 共享 v/a/j 预算、刹车规划、量化处理和回正。 | 静止目标收敛、±180°跨界、俯仰边界、急反向、换目标、失目标；列出所有约束退化，不隐瞒末端跳变。 |
| D：几何与战斗衔接 | 稳定选点、统一预测、优先级、最终攻击验证与 Latest/Legacy 状态整合。 | 目标走动/跳跃/遮挡，前方友军或可交互实体，hurtTime、距离边界、物品切换、Critical 延期、解除格挡取消。 |
| E：迁移与交付 | 新分组、旧键迁移、文档、各支持版本编译与已有相关流程验证。 | 同配置不重复迁移；隐藏项没有不透明依赖；26.1、26.2、26.3 对应工程版本均检查。 |

评估报告同时给出：首次射线可命中时间、移动目标角误差的中位数/P95、超调量、连续失去可命中状态的时长、因为转向而错过的攻击窗口、实际 v/a/j 的峰值/P95、相同输出/相同步长连段长度、自相关，以及各类重置/退化次数。参考目标运动、灵敏度和采样时间轴必须一致。

候选方案必须在**相同运动预算和场景**下证明 Balance 的跟踪延迟有改善；若只是把预算调大，报告应写成预算与延迟的取舍。预算满足属于确定性检查，延迟和统计分布属于对照实验，服务器效果属于另一个尚未验证的问题。本文没有给出已经通过这些测试的结论。

<a id="source-index"></a>

## 5. 源码索引与核对边界

### 5.1 Moons

| 内容 | 源码 |
| --- | --- |
| 参数键、默认值、范围、可见条件 | [SilentAuraConfig](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraConfig.java) |
| 事件、激活、角度提交与回正 | [SilentAuraRuntime](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraRuntime.java) |
| Lock 响应与常量 | [AimProfileA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimProfileA.java) |
| Balance 响应与常量 | [AimProfileB](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimProfileB.java) |
| 包级预算采样 | [AimSamplingA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimSamplingA.java) |
| 包级速度/加速度处理 | [SmoothF](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothF.java)、[SmoothK](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothK.java) |
| FullLock 专用步长缩放 | [SmoothJ](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothJ.java) |
| 目标历史的现有分组 | [SilentAuraModes](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraModes.java) |

### 5.2 LiquidBounce

各参数小节已链接具体源码。额外核对：

- [模块公共 Bind/Hidden](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/ClientModule.kt)。
- [配置多选与默认值构造](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/config/types/group/ValueGroup.kt)。
- [CriticalsSelectionMode](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/criticals/ModuleCriticals.kt)。
- [MovementCorrection](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/MovementCorrection.kt)。
- [部位排除定义](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/exempts/ExemptBoxPart.kt)。
- [最佳点排除定义](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/exempts/ExemptBestHitVector.kt)。
- [NormalDistribution 点击算法](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/NormalDistributionPattern.kt)。

### 5.3 本文能确认与尚未验证的内容

- 已确认：当前本地源文件中的设置声明、枚举、默认值、范围、主要读取位置和调用链。
- 本文的特殊路径提示以本地快照为准，不能泛化成所有历史/未来 LB 版本。
- 尚未验证：用户实际加载的配置、游戏内最终运动曲线、实际服务器反馈、任何参数对检测结果的定量改善。
- 最初的源码分析没有修改功能代码；后续按确认加入了第 8 节的 AimPoint 处理器并进行编译/自动验证。未注入游戏或进行服务器实测，LB 工作区未修改。

<a id="polar-failure"></a>

## 6. Polar 历史与高版本 Failure 的实际设计

### 6.1 LiquidBounce 是否做过针对 Polar 的 KillAura 策略

**历史上做过可供 KillAura 使用的针对性转向随机化工作；不能据此认定当前 Nextgen 有独立 Polar 算法，或已经解决现代服务器检测。**

| 证据 | 可以确认 | 不能推出 |
| --- | --- | --- |
| 2025-02-04 的 Legacy 提交 `a548ec63af863c0ede84e91f31b8b073e4589c8d`，PR #5528 | 增加 `LazyFlick` 随机化模式及 `MinRotationDifferenceResetTiming`，提交正文明确写了“dedicated to polar anticheat”。当时 KillAura 持有 `RandomizationSettings`，并传给 `searchCenter()`，因此并非仅给 Aimbot 使用。 | 不能当成目前 Nextgen 的专用 Polar 模式；也不能证明效果延续至今。 |
| 本机 Nextgen 快照 `e67fdf9b70131f478c5894cf9de1f2d4aa7a95ce` | KillAura 注册的是通用角度平滑器与 Fail/ShortStop，相关源码未发现独立 Polar 分支。其他模块中的 Polar Spider/Fly 是别的功能。 | 搜不到命名分支不等于所有通用算法从未受 Polar 需求影响；无法排除脚本或远端配置差异。 |
| 官方仓库 Issue #8719，2026-07-22 | 用户报告 Nextgen 0.39.0 使用 Polar 配置一段时间后伤害减少；问题关闭为 not planned。 | 用户报告不等于已确认根因；关闭也不表示成功修复。其版本栏为 `26.2-1.8`，不能拿它证明原生现代战斗协议的效果。 |

历史提交和当时文件：[LazyFlick 提交](https://github.com/CCBlueX/LiquidBounce/commit/a548ec63af863c0ede84e91f31b8b073e4589c8d)、[当时的 KillAura](https://github.com/CCBlueX/LiquidBounce/blob/a548ec63af863c0ede84e91f31b8b073e4589c8d/src/main/java/net/ccbluex/liquidbounce/features/module/modules/combat/KillAura.kt)、[当时的 RandomizationSettings](https://github.com/CCBlueX/LiquidBounce/blob/a548ec63af863c0ede84e91f31b8b073e4589c8d/src/main/java/net/ccbluex/liquidbounce/utils/rotation/RandomizationSettings.kt)。提交内容与调用关系已在本地 Git 历史核对。[Issue #8719](https://github.com/CCBlueX/LiquidBounce/issues/8719) 为官方仓库的用户反馈，证据性质与源代码不同。

因此，参考 LB 时应记录“分支 + 提交 + 客户端版本 + 连接协议”。只说“高版本 LB 能用”无法区分高版本客户端连接旧协议服，还是原生现代战斗。

### 6.2 Failure 在这份源码里对应哪几件事

本机 KillAura 没有一个字面名为 `Failure` 的独立子模块。结合前文，主要对应以下两项，以及关联的反馈功能：

| 功能 | 所在层 | 实际作用 |
| --- | --- | --- |
| `Rotations → Fail` | 角度处理器 | 一段时间内给旋转结果附加偏移。 |
| `FailSwing` | 点击/动作执行 | 附近存在目标但未正常命中时，符合条件才进行空挥。 |
| `FailSwing → NotifyWhenFail` | 本地提示 | 空挥时画框或播声音；不是通过服务器拒绝攻击的反馈来判定失败。 |

两项主功能默认都关闭，可分别使用。`Fail` 不直接调用攻击或挥手；`FailSwing` 不负责故意把准星移走。官方概述也分别描述了 [Rotations.Fail](https://liquidbounce.net/docs/modules/shared-settings/rotations) 与 [KillAura.FailSwing](https://liquidbounce.net/docs/modules/combat/killaura)，下面的细节以本机固定快照源码为准。

### 6.3 Rotations.Fail 的触发与输出

完整参数已列于 3.5.6。实现流程如下：

1. 在 `GameTickEvent` 的 FIRST_PRIORITY 阶段抽取 0–100 的随机数；若小于 `Rate`，触发或重新触发 Fail。
2. 抽取本次持续 tick 数；抽取水平、垂直偏移及各自正负号；保存为 `shiftRotation`，把 `ticksElapsed` 置 0。
3. 没触发的 tick 才把 `ticksElapsed` 加 1。处于 Fail 时也可以再次触发，重新抽取并重置计时。
4. `running && ticksElapsed < currentTransitionInDuration` 时，处理器使用下面的公式；拿不到 `previousRotation` 时直接返回输入角度。
5. 到期后不再附加这段偏移，返回输入的目标角度，由整条转向流程继续处理。

```text
Fail 输出 yaw = 输入 yaw
              + (previousRotation.yaw - serverRotation.yaw) × Factor
              + 本段 yaw 偏移

Fail 输出 pitch 同理。
```

这里的“输入”通常已经是本次 AngleSmooth 处理过的结果。该公式的历史差直接相减；它不是对“剩余瞄准误差”乘 Factor。`serverRotation` 在 LB 的 lag/freezing 情况下还可能取理论值，不能一律等同于实际已发送角度。

几个特别容易误解的地方：

- `Rate=3%` 是**每游戏 tick 的触发概率**，不是每 100 次攻击打空 3 次。若事件持续运行且按独立抽样计算，20 次抽样至少触发一次的概率为 `1 - 0.97^20 ≈ 45.6%`；这仍不是空挥率或命中失败率。
- 默认持续区间 1–4 tick，在 20 TPS 下约 50–200 ms；区间允许 0，此时该次触发不产生持续 Fail。中途重触发可以延长/替换状态。
- `TransitionInDuration` 这个名字容易让人以为是渐进恢复；本实现**没有按进度线性或曲线地减小偏移**，只用计数判断是否附加偏移。
- 偏移在本段状态中保存，每次 `process()` 都把它加到当次输入；不是固定每 tick 重新抽一组角度，也不是只改变一次后不再参与后续输出。
- 该 tick 处理器没有在函数内部检查是否已锁定实体，所以不是“每次计划攻击时才抽样”的设计。

来源：[FailRotationProcessor](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/features/processors/FailRotationProcessor.kt)、[RotationManager](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/RotationManager.kt)。

### 6.4 Fail 位于平滑器之后：对我们有什么意义

这份 Nextgen 源码的常规顺序是：

```text
目标角 → AngleSmooth → Fail（启用时）→ ShortStop（启用时）
       → normalize：灵敏度量化与 Pitch 裁剪 → 当前旋转
```

由 [RotationsValueGroup](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/RotationsValueGroup.kt) 构造处理器顺序，[RotationTarget](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/RotationTarget.kt) 顺序执行，[Rotation.normalize](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/data/Rotation.kt) 做最终量化。`OnTick` 等特殊时机仍应另查实际提交路径，不能用这张图概括所有模式。

例如平滑器已经输出 100°，历史差暂为 0，本段附加 +8°，Fail 直接给出 108°。它不重新通过前面的加速度平滑器。启用、重触发或结束偏移时，都**可能**在最终差分中形成突变；是否出现及有多大还取决于其他处理器和实际轨迹。最终量化不会自动修复这类突变。

所以它提供的是“短暂瞄准偏离”的行为，而不是最终 v/a/j 约束。是否真的打空，还取决于目标角宽、距离、射线检查、攻击时间点和 FailSwing 是否能执行。几度的偏移在近距离仍可能命中较宽的目标。

对我们：先解决第 4 节的一条最终轨迹。如果未来确实要加入偏离行为，应将它作为有起止状态的目标变化，再经过统一运动约束；可以借鉴行为分层，不能把 LB 这段后置加角度原样当成 Balance 特征问题的修复。

### 6.5 FailSwing 如何与高版本攻击衔接

KillAura 在没有目标，或当前旋转/距离不能形成有效命中时，可能进入 `dealWithFakeSwing()`。中间也可能因扫描范围格挡、解除格挡等待等条件提前返回，所以不是每次没瞄准都会空挥。

真正进入空挥还需满足：

1. FailSwing 开启，`canAttackNow()` 允许使用当前物品，且没有相应的界面阻挡等条件。
2. 传入的目标仍存在，或能在附近找到敌人；距离不超过普通交互范围加本次 `AdditionalRange`。
3. `mc.hitResult?.type == MISS`。它检查的是当前客户端命中结果字段，不只检查 KillAura 自己刚算出的实体射线；这两份数据如何同步，需要结合命中结果更新路径分析。
4. 进入共用的 `prepareForAttack`，通过点击计划、空挥冷却、武器蓄力以及物品使用/解除格挡等检查。
5. 适用时设置 `mc.missTime = 10`，然后 `player.swing(MAIN_HAND)`，发起本地失败提示并返回 `true`，让 Clicker 计入一次已执行点击。

**此路径没有调用 `attackEntity(target)`。**它是挥手动作，不是发一个明知打不到的实体攻击。通常会经玩家挥手路径发送动画包；NoSwing 等模块可以改变是否显示/发送，因此不能把它视为无条件一定有一个 swing 包。

参数 `AdditionalRange` 默认 2.5–3 格，在实体攻击事件后重新抽样；它决定附近有没有值得空挥的目标，不增加真实攻击距离。

来源：[ModuleKillAura](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/ModuleKillAura.kt)、[KillAuraFailSwing](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/features/KillAuraFailSwing.kt)、[KillAuraClicker](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/KillAuraClicker.kt)。

### 6.6 高版本的两个 cooldown 不能混为一谈

| 机制 | LB 实现 | 含义 |
| --- | --- | --- |
| `AttackCooldown` / `missTime` | Clicker 默认检查 `mc.missTime > 0`；FailSwing 在 `interaction.hasMissTime()` 时写入 10。 | 对空挥后再次尝试点击的等待；是否设置取决于游戏交互模式，并非代码按“高版本”统一强制。 |
| `ItemCooldown.Minimum` | `attackStrengthTicker / currentItemAttackStrengthDelay >= nextCooldown`，默认阈值区间 1–1。 | 武器蓄力是否达到本次阈值；与上面的 10 tick 计时是两个变量。 |
| `ItemCooldown.newCooldown()` | 从 Minimum 区间重新抽取下一次阈值。 | **没有在这个函数里把原版 `attackStrengthTicker` 清零。**不能因名字里有 newCooldown 就作此推断。 |
| 成功的空挥回调 | Clicker 更新自己的点击计数、最后点击时间、距上次点击的 tick，并调用 `newCooldown()`。 | 说明空挥进入共用调度，而不是旁路无条件每 tick 挥手；不证明服务器收到伤害。 |

KillAura 的 ItemCooldown 还有破盾、重锤和预测离开攻击范围等放宽阈值的条件，见第 3 节。因此“所有空挥都必须等到 100% 蓄力”也不是绝对成立的描述。

来源：[Clicker](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/Clicker.kt)、[ItemCooldown](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/ItemCooldown.kt)、[KillAuraClicker](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/KillAuraClicker.kt)。这里区分的是 LB 显式执行的行为，原版与跨版本协议的最终冷却行为仍应在对应运行环境验证。

### 6.7 本次重构取舍

- 采用：角度策略、命中验证、点击执行、空挥/失败提示分别负责不同事情；高版本蓄力与空挥计时分开。
- 先解决：稳定选点、消除重复追赶、最终输出连续性、动作窗口一致性，以及只读的轨迹统计。
- 后续可选：有清晰几何触发条件的空挥；有明确需求后再设计主动偏离/暂停，默认不把它们作为新 Balance 的依赖。
- 需要实测才能回答：延迟是否下降、轨迹统计如何变化、在特定协议与服务器上有什么反馈。LazyFlick 的历史、Fail 的存在和某份 Polar 配置，均不能代替这些数据。

<a id="legacy-rotation-details"></a>

## 7. Legacy：AngleChange、YawRandomization 与 Legitimize

本节专门解释用户补充的三个 Legacy 设置。依据本地 `origin/legacy` 的 **b100 / `03bc91a3c34031d1ea7400ef3b67c00adf6a4c6e`**，通过 `git show` 读取，未切换或修改 LB 工作区。

固定版本源码：[RotationSettings.kt](https://github.com/CCBlueX/LiquidBounce/blob/03bc91a3c34031d1ea7400ef3b67c00adf6a4c6e/src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationSettings.kt)、[RotationUtils.kt](https://github.com/CCBlueX/LiquidBounce/blob/03bc91a3c34031d1ea7400ef3b67c00adf6a4c6e/src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt)、[RandomizationSettings.kt](https://github.com/CCBlueX/LiquidBounce/blob/03bc91a3c34031d1ea7400ef3b67c00adf6a4c6e/src/main/java/net/ccbluex/liquidbounce/utils/rotation/RandomizationSettings.kt)。

### 7.1 HorizontalAngleChange / VerticalAngleChange 是最大速度吗

**可以理解为每次转向更新的水平/垂直名义步长预算，但不能理解成最终发包各轴绝不超过的严格速度上限。**

两项默认均为 `180–180`，可设范围 `1–180`。每次读取 `horizontalSpeed`、`verticalSpeed` 时各自从区间抽样。常规转向由 `RotationUpdateEvent` 推进；单位首先是“度/次更新”，在每 tick 一次的常规路径中才能按 °/tick 理解，不能直接当成 °/s。

普通路径先按剩余误差方向分配预算。设 `ey` 为 yaw 误差，`ep` 为 pitch 误差，`D = sqrt(ey² + ep²)`：

```text
水平分配预算 = abs(ey / D) × 本次水平抽样值
垂直分配预算 = abs(ep / D) × 本次垂直抽样值
各轴拟定步长 = 将该轴误差裁到相应分配预算内
```

零误差由安全除法处理。例：yaw 误差 60°、pitch 误差 80°，两轴抽样值都是 30°，则这一步先得到水平 18°、垂直 24°的预算，而非两轴都转 30°。只需水平转动且误差足够大时，水平才获得完整的 30°预算。

随后还存在三类变化：

- 对非零误差，yaw 步长再加其自身约 ±3% 的扰动，pitch 约 ±2%；这段代码不以 Legitimize 开关为条件。
- Legitimize 开启时，根据历史步长进行插值；如果历史步长较大，插值后的本次步长可以高于刚抽到的较小预算。
- 小角阈值、暂停与灵敏度量化继续影响结果；内部 `instant` 路径还会改用 180/180 并关闭 Legitimize。

所以更准确的说法是：**AngleChange 控制想转多快的基础预算，后续整个管线决定实际输出。**它不控制最大 yaw 朝向、总 FOV 或加速度上限。对于我们新 FullLock，若 UI 写“最大速度”，应在最终量化输出上真正约束，并显示无法满足约束时的原因。

### 7.2 YawRandomization 实际随机什么

它属于 **选点时的参考朝向扰动**。常规 `searchCenter()` 先选一个偏好的参考朝向，复制为 `currRotation`；Randomization 修改这份副本；随后从符合命中盒、距离和可见性条件的候选朝向中，选择离该参考最近的结果。

```text
原参考朝向 → Randomization 扰动参考
           → 搜索/验证候选命中点 → 选离新参考最近的目标角
           → AngleChange / Legitimize → 最终量化
```

这会改变“更愿意瞄命中盒的哪一处”和追踪趋势，通常不会把这个偏移原样直接加到最终包上。`OutBorder` 等提前返回路径还可能跳过这一常规过程。它与 Nextgen 的后置 `Rotations.Fail` 所在层不同。

#### 配套参数

| 参数 | 默认值；范围 | 实际意义 |
| --- | --- | --- |
| RandomizationPattern | None；None / Zig-Zag / LazyFlick | None 不启用；另外两项决定如何扰动参考。 |
| YawRandomizationChance | 0.8–1.0；0–1 | 代码判断为 `Math.random() > 抽样阈值`；**阈值越大，触发越少**。不是名字直觉里的“值越大越经常抖”。 |
| YawRandomizationRange | 5–10°；0–30 | Zig-Zag 的水平参考偏移幅度；方向通常取参考朝向相对历史朝向的变化方向，变化为 0 时随机左右。 |
| YawSpeedIncreaseMultiplier | 50–120%；0–500% | LazyFlick 用它乘“参考 yaw 相对 `lastRotations[2]` 的角差”，得到参考偏移量；不是修改最终速度上限。 |
| PitchRandomizationChance | 0.8–1.0；0–1 | 和 yaw 一样使用大于阈值才触发的条件。 |
| PitchRandomizationRange | 5–10°；0–30 | 参与垂直参考偏移，两个模式的公式不同，见下。 |

固定 Chance=0.9 时，每次调用的随机条件约有 10% 概率通过。默认阈值均匀抽在 0.8–1.0，平均也约 10%；LazyFlick 还要满足其几何条件，因此实际应用偏移的比例可能更低。这个调用概率不等于每 tick 概率，也不等于攻击失误率。

#### Zig-Zag 与 LazyFlick 的差别

- **Zig-Zag：**通过随机条件后，yaw 加 `抽样幅度 × 变化方向`；pitch 加 `抽样幅度 × 垂直变化方向`。没有 LazyFlick 那个“历史射线仍能穿盒就不应用偏移”的保护。名称不能理解成机械地每次左右交替。
- **LazyFlick：**随机取一个保存的历史朝向，检查它的射线能否穿过目标盒；只有没交点时才把本次计算的偏移应用到参考上。水平偏移为 `Multiplier / 100 × yawMovement`。它结合了历史方向与是否仍可穿盒的条件，不是持续无条件加同幅度噪声。
- **本快照的垂直非对称细节：**LazyFlick 的 pitch 代码写的是 `pitchRandomizationRange.random() + pitchMovement`，其中 pitchMovement 是 -1、0 或 1 的方向量；不是把幅度乘以方向。默认幅度为 5–10 时，该表达式偏向正 pitch。不能把它描述成严格对称的上下随机抖动。

以上说明源码怎样工作，不等于这些行为改善检测结果。对我们可参考的是“稳定保留可用射线”和“在选点层表达偏移”，同时需要保留最后的几何复查与运动约束。

### 7.3 Legitimize 做了什么

**核心是把本次拟定角度步长，向历史步长做带随机系数的插值，让速度变化具有惯性。**默认关闭，主要实际逻辑位于 `applySlowDown()`。

```text
historyStep = serverRotation 与 lastRotations[1] 的该轴角差
wantedStep  = 本次方向分配、裁剪、小幅扰动后的拟定步长
newStep     = historyStep + (wantedStep - historyStep) × k
```

- 已在运动时，`k` 从 0.3–0.7 抽取。
- 历史步长为 0 时，系数区间为 `0.1 + inc` 到 `0.5 + inc`，其中 `inc = 0.2 × clamp(abs(wantedStep)/50, 0, 1)`。
- 还配合 `MinRotationDifference` 及 `OnStart/OnSlowDown/Always` 做小步长停顿或避免末端过度变慢。部分阈值判断即使关闭 Legitimize 也仍执行。

例如上次步长是 8°，本次想转 20°，`k=0.5`，输出先变成 14°；下次仍想转 20°且 `k=0.5`，才到 17°。它改变的是**相邻步长之间的过渡**，因此和单纯缩小 AngleChange 不是同一种处理，也可能带来跟踪延迟。

它没有对 `a = newStep - historyStep` 设置固定上限，也没有限制相邻加速度的变化。系数随机、小角阈值切换及目标反向都需要观察实际输出，不能把开关名称等同于“严格平滑”或“像真人”。

本 b100 快照还有一处应按实际效果解释的代码：

```text
baseYawSpeed * 随机系数
basePitchSpeed * 随机系数
```

这两项虽在 `if (legitimize)` 内，但没有赋值回去，表达式结果被丢弃；不能把注释中的“imperfect correlation”当成已经生效的速度扰动。另一个 ±3%/±2% 步长扰动则在该 if 外，所以关闭 Legitimize 不等于关闭所有随机变化。

### 7.4 三者和我们的参数怎样对应

| LB Legacy 参数 | 作用对象 | 我们重构时的明确表达 |
| --- | --- | --- |
| Horizontal/VerticalAngleChange | 本次转向的基础预算 | 水平/垂直最大速度，最终输出验证；不把随机区间本身当成连续性保证。 |
| Yaw/PitchRandomization | 搜索命中点时偏好的参考朝向 | 可用射线保持、局部落点漂移与明确采样时机。 |
| Legitimize | 步长向历史步长的随机插值 | 跟随响应、速度/加速度/jerk 约束分开；一个最终控制器承担连续性。 |
| MinRotationDifference | 小角动作是否继续或停顿 | 与灵敏度量化相关的明确容差和停止条件，避免固定死区造成抖动或长期追不上。 |

这也解释了为什么“把 FullLock 暴露更多参数”有意义，但不宜再放一个包办所有行为的 Smooth/Legitimize 开关：用户需要分别知道，是改变了落点、跟随速度，还是改变了加减速的过程。FullLock 拟暴露项目见 4.9.1。

<a id="aimpoint-implementation"></a>

## 8. AimPoint 第一阶段：已实现的 Lazy 与 Gaussian

### 8.1 LB Nextgen 的 AimPoint 是什么流程

按第 3 节的本地 Nextgen 快照，流程是：

```text
取得实体在指定预测时刻的盒子，考虑 pickRadius
    → 从眼睛视角投影采样盒表面候选点
    → 排除指定部位/最近点附近区域
    → 选择离眼睛较近的剩余点
    → Delay → Lazy → Gaussian（各自开启才处理）
    → 按处理后的点建立朝向偏好
    → raytraceBox 结合距离、遮挡求最终可用角度
```

`findPoint()` 虽支持预测 tick 参数，但本快照 KillAura 的常规调用没有传该参数，使用默认 0。不能仅因内部用了预测器就断言这里总是提前几 tick 瞄准。

这套结构不等于我们 Center／Closest 的模式选择：LB 先取得一个偏好点，然后围绕它进行射线求解。部位排除、Lazy、Gaussian 都是可以组合的处理步骤。排除候选全部为空时仍会回退到 bestHitVector，因此不是绝对禁止某部位。

源码：[PointTracker](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/PointTracker.kt)、[PointFinding](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/utils/PointFinding.kt)、[ModuleKillAura.findRotation](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/killaura/ModuleKillAura.kt)。

本地参考实现还需要区分几个细节：

- Lazy 保存的是整份旧 `PointInsideBox`，包含旧世界坐标及盒子。跨过阈值时虽然保存了新点，该次调用仍返回之前的局部变量，下一次才读到新点。
- Delay 在调用时递减，不是独立按游戏 tick 计时。本阶段不引入 Delay。
- Gaussian 通过 `point + currentOffset` 返回结果，而 `PointInsideBox.plus()` 同时平移 point 和 box。因此不能把 LB 这条路径描述成“在原始实体盒内只移动一个点”。
- Gaussian 的单轴启用与 Dynamic 传参限制见 3.6；我们没有照搬这些条件。

源码：[PointProcessorLazy](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/features/PointProcessorLazy.kt)、[PointInsideBox](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/PointInsideBox.kt)、[PointProcessorGaussian](E:/McEnv/skid/lb/LiquidBounce/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/point/features/PointProcessorGaussian.kt)。

### 8.2 我们本次落地的流程

```text
原 Center／Closest 与现有模式选点逻辑
    → Lazy：保持目标盒中的局部坐标
    → Gaussian：平滑变化的零均值偏移
    → 裁到当前真实盒内，复查遮挡和实际范围
    → 不可用则尝试未加偏移的保持点、原基础点
    → 既有预测/转向/量化/最终攻击检查
```

Center／Closest 的基础实现文件没有改动；两个效果都关闭时直接返回原基础点，不消耗随机数。新处理器只接入 SilentAura 的跟踪选点，候选实体扫描不推进处理器，AimAssist 也不受此次后处理影响。

**Lazy 保持的是目标身上的相对位置。**例如保持在目标盒的 `(50%, 70%, 50%)`，目标向右移动时该点随当前盒立即向右移动，身体姿态/尺寸变化时重新投影。阈值比较的是当前盒内的保持点与新基础点，不把实体整体移动当成“等待刷新”的理由。

**Gaussian 偏移是什么：**从以 0 为中心的高斯分布抽取 X/Y/Z 偏移，小偏移更常见，大偏移更少见。水平标准差控制 X/Z，垂直标准差控制 Y；各轴尾部截到 ±3σ，再让当前偏移逐渐靠近本次抽到的目标偏移。比如水平标准差 0.05 格，原始每个水平轴采样不会超过 ±0.15 格；后续插值、盒边界与遮挡回退还会进一步改变实际偏移。

处理器按逻辑 tick 推进保持决策和偏移状态，同 tick 多次读取不会重抽或再次插值，但仍用最新盒子与视线复查。换世界、换实体实例、tick 回退、超过 20 tick 的观察中断、相邻观察盒中心移动超过 8 格、修改这些处理参数时重建本层历史。没有可用基础点或关闭效果也会清理本层状态。

这不是把 Gaussian 添加到最终发包角度上。已有 Center Wander、转向阶段的 Jitter、预测和高度稳定仍会处理后续轨迹；本次不声称仅增加 Gaussian 就降低 v/a/j 或服务器检测特征。若比较新效果，应分别记录启用组合，避免把原有漂移的影响归给 Gaussian。

### 8.3 验证与下一阶段

自动验证入口为 `verifySilentAuraPoints`，已加入项目的 `check` 依赖。测试覆盖：默认关闭透传、不消费随机数；局部锚点随平移/尺寸变化；阈值跨越；同 tick 遮挡失效；超距回退；水平/垂直独立开启；高斯尾部与盒内边界；重复读取不推进状态；换目标/世界、时间回退、观察中断、瞬移及失点后的重置。

验证源文件：[SilentAuraPointVerification](E:/McEnv/moons/src/test/render/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraPointVerification.java)。这些是几何与状态测试，不是游戏中命中率、最终角度序列或服务器效果测试。

本次实际验证结果（JDK 25、离线 Gradle、各版本独立输出目录）：

| 工程 Minecraft 版本 | 编译与 `verifySilentAuraPoints` |
| --- | --- |
| 26.1.2 | 通过 |
| 26.2 | 通过 |
| 26.3-rc-3 | 通过 |

四个本次修改/新增的 Java 文件已按工程格式化规则处理，改动通过空白检查。没有执行完整 `checkAllVersions`，也没有进行游戏注入或服务器验证。

现有入口已按用户后续要求改名 Optimize，保存键 `silentaura.matrix` 及原有行为保留，调试 HUD 的 profile 名同步变为 `optimize`。新的策略选择、独立角速度/加速度参数和最终硬约束尚未实现，设计及组件实验见下节。

<a id="rotation-simulation"></a>

## 9. Optimize、角速度/加速度与 Smooth 饱和实验

### 9.1 当前可以直接限制这四项吗？

**没有分别暴露 Yaw speed、Pitch speed、Yaw acceleration、Pitch acceleration。**`Smooth` 控制响应，`Optimize` 切换内置预算；FullLock 的 Angle step 是两轴共用的步长上限，Smoothing 只是步长缩放。`motion.maxAcceleration` 是目标平移预测的加速度，不能拿它限制玩家转头。

当前内部值如下；包级单位换算假设每 50 ms 成功发送一次。表中是算法预算，**不是最终已发送序列在所有场景下的严格保证**。

| 层级/模式 | Yaw | Pitch | 说明 |
| --- | --- | --- | --- |
| 帧级 Lock 基础速度 | 1080°/s | 760°/s | 后续乘 stepScale，不直接等于最终包速度上限。 |
| 帧级 Balance 基础速度 | 660°/s | 440°/s | 带速度惯性。 |
| 帧级 Balance 基础加速度 | 3600°/s² | 2600°/s² | 同样受 scale 影响；Lock 的普通帧级路径没有这项惯性限制。 |
| 包级普通步长 | 48°/次 ≈ 960°/s | 32°/次 ≈ 640°/s | Lock/Balance 共用。 |
| Optimize 开、近身重叠 | 18°/次 ≈ 360°/s | 沿用普通规则 | Yaw 在加速度计算后再裁剪，切入此限制可能突然减速。 |
| Optimize 开、Lock 加速度预算 | 11–23°/次² ≈ 4400–9200°/s² | 1.45–2.10°/次² ≈ 580–840°/s² | 全部 urgency 的外包范围，不是每次从整个范围抽样。 |
| Optimize 开、Balance 加速度预算 | 6–12°/次² ≈ 2400–4800°/s² | 1.05–1.65°/次² ≈ 420–660°/s² | 抽样后与上一份预算按 0.3 插值。 |
| Optimize 关、Lock 加速度预算 | 20–39°/次² | 3.2–5.7°/次² | 更快的内部配置。 |
| Optimize 关、Balance 加速度预算 | 12–25°/次² | 2.2–4.1°/次² | 同样不是四个可填写的参数。 |
| FullLock | 两轴共用 Angle step × `(1 − 0.5 × Smoothing)` | 同左 | 没有按上次步长限制角加速度；默认可从静止一步转 90°。 |

源码：[SmoothA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothA.java)、[SmoothF](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothF.java)、[SmoothK](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothK.java)、[AimSamplingA](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/aim/AimSamplingA.java)、[SmoothJ](E:/McEnv/moons/src/minecraft/shared/java/com/blanoir/moons/client/utils/rotation/smooth/SmoothJ.java)。

### 9.2 应该怎样限制速度和加速度？（下一阶段设计）

固定周期 Δt 下，每轴分别定义：

```text
本次步长 Δθ = 本次实际发送角度 − 上次实际发送角度
角速度 v = Δθ / Δt
角加速度 a = (v − 上次 v) / Δt
jerk j = (a − 上次 a) / Δt
```

Yaw 差值要取绕 ±180° 的最短弧；Pitch 使用普通差值并保持在 ±90°。例如 `179° → −179°` 是转 2°，不能记成 −358°。

若 `Yaw speed = 200°/s`、`Yaw acceleration = 800°/s²`，20 Hz 下每次步长不超过 10°，相邻步长最多改变 2°。从静止加速可以是 `2° → 4° → 6° → 8° → 10°`；反向时也要先减速，不能从 +10° 一下跳到 −10°。

建议将四项独立值放入 Optimize 的明确策略下，主 Smooth/Response 只决定希望多快接近目标。控制器在最后一个输出入口执行：

1. 用角度误差生成期望速度，结合剩余角度提前刹车。`sqrt(2 × aMax × |误差|)` 可作为连续模型的刹车速度参考，离散更新还要预留一个更新周期的行程。
2. 将速度限制在 `[-vMax, vMax]` 与 `[vPrevious − aMax×Δt, vPrevious + aMax×Δt]` 的交集中，Yaw/Pitch 各算各的。
3. **结合鼠标量化选择可行步长。**不能先限幅再随意 round；应把允许步长区间换成整数量化格的区间，选其中最接近期望的一个。
4. 成功发送后，用实际角度回算速度/加速度并更新历史。候选角度、取消发送、同 tick 重复读取不应冒充实际发送。
5. 量化格过粗、突然降低速度上限、接近 Pitch 物理边界等场景可能使约束无交集。必须明确处理优先级并记录约束冲突，不能宣称所有条件同时无条件成立。

重置、传送修正和所有权切换要明确重建历史。卡顿/漏发时应根据采用的逻辑时基处理状态，并同时记录实际发送时间间隔。限制加速度仍不等于限制 jerk；随机抽取加速度预算也不能证明“特征”变少。

### 9.3 本轮模拟方法与覆盖范围

入口：[SilentAuraRotationSimulation.java](E:/McEnv/moons/src/test/render/java/com/blanoir/moons/client/module/impl/combat/silentaura/SilentAuraRotationSimulation.java)。在仓库根目录执行：

```powershell
.\gradlew.bat simulateSilentAuraRotation --offline --console=plain
```

需要项目要求的 JDK 25 和已有 Gradle/Minecraft 缓存。输出为 `build/rotation-simulation/summary.csv`（每组/每种子指标）和 `traces.json`（精选回放）。本轮快照与完整数值表见 [转头模拟报告](E:/McEnv/moons/docs/silentaura-simulation/README.md)。模拟任务单独运行，不加入默认 `check` 的性能负担。

本轮实际完成 **93 组设置 × 7 场景 × 8 固定随机种子 = 5208 次运行**，每次 4 秒、80 次模拟成功发送：

- 模式：Lock、Balance、FullLock；Lock/Balance 分 Optimize 开/关。
- Smooth：0.05、0.3、0.58、0.8、1.0。
- FullLock Angle step：30、60、90、120、180；Smoothing：0、0.25、0.5、0.75、1，交叉组合。
- 额外环境组合：30/60/144/240 FPS，灵敏度 0.1/0.5/1；本轮该组合固定 Smooth=0.58。
- 场景：水平 90°、垂直 60°、对角 90°/45°、1 秒时反向、正弦移动、179°→−179°、第 4 次更新进入近身 Yaw 裁剪。
- 指标：首次进入 1° 合成误差、稳定到达、误差 RMS、静态目标两轴超调，以及最终量化后 Yaw/Pitch 的速度、加速度、jerk 峰值和加速度 P95。

`packet` 直接向真实 PacketRotationSmoother 喂目标角；`frame+packet` 先经过真实 SmoothA，再进入真实包级控制器与 QuantizerA。随机源通过注入固定种子复现，生产默认构造器仍使用原来的 RandomMath，采样公式和顺序未改变。

**这不是整个 SilentAura 的游戏仿真。**`frame+packet` 将 stepScale 固定为 1，关闭前馈，不调用目标选择、AimPoint、预测、走廊保点、Balance 的噪声 correction 等环节；假设每 tick 成功发送，帧与 tick 起点对齐。Return smooth、Jitter、Wander、预测、Lazy/Gaussian 数值扫参、攻击和遮挡参数未纳入本轮转头对比。此前 AimPoint 测试只证明其几何/状态行为。不能把这 5208 次运行说成“所有参数、所有取值都验证了”，也不能据此判断命中率或服务器效果。

稳定到达：从该次发送开始直到 4 秒结束始终保持在 1° 内，并至少留下 250 ms 观察窗口；没有满足则为 −1。动态目标不计算到达/超调，记 −1。峰值从初始静止状态开始计算，包含起步和刹车；有限差分的速度/加速度/jerk 是离散输出指标，不是连续曲线导数。

### 9.4 Smooth 是否超过某个值就没意义？

**会出现局部收益饱和，但不存在源码写死的全局阈值。**Lock 的帧响应为 `19+23×Smooth`，Balance 为 `12+16×Smooth`，都在整个取值范围内继续变化。最终输出可能被下面的环节限制：

1. 帧级速度预算：大角度时上游希望转得更快，但已经到速度上限。
2. Balance 帧级速度惯性、包级加减速预算：上游目标更激进不一定使实际步长增加。
3. 包级 48°/32° 步长上限，以及近身 Yaw 18° 限制。
4. 接近目标时，鼠标量化把很小的差异舍掉。

本轮对角 90°/45°、60 FPS、灵敏度 0.5、8 个种子的结果：

| 模式 / Optimize | Smooth=0.58 稳定到达均值 | 0.8 | 1.0 | 含义 |
| --- | --- | --- | --- | --- |
| Lock / 关 | 456.25 ms | 456.25 ms | 456.25 ms | 同一指标已饱和，但不能据此说整条轨迹相同。 |
| Lock / 开 | 687.50 ms | 687.50 ms | 668.75 ms | 更高响应在此场景仍有少量收益。 |
| Balance / 关 | 812.50 ms | 812.50 ms | 825.00 ms | 继续提高不保证更早稳定。 |
| Balance / 开 | 1100.00 ms | 1100.00 ms | 1131.25 ms | 叠加惯性/刹车造成的超调会抵消响应收益。 |

在同一场景、种子 20260915 下，Balance / Optimize 关的 Smooth 从 0.05 提高到 0.58、1.0，Yaw 超调从 4.2° 增至 30.45°、41.55°。这是当前隔离模型中双层动态响应的具体问题，仍需游戏轨迹确认其实际程度。

因此优化重点应是协调响应与提前刹车、统一最终速度/加速度控制，而不是继续增大 Smooth 或仅降低其默认值。到达时间、超调、速度、加速度和 jerk 要分别看：某个峰值不变、某个速度下降，都不能代表整条轨迹已经改善。

### 9.5 外部 legit 记录的对照分析

用户随后提供了 Kaggle 的 legit.csv。本轮完成 20,341,000 行的全量数据审计，发现原 accel_pitch 混用了两轴历史值；重算差分后分析分布，并完成按 UUID 隔离的小型历史回归试验。结果、来源边界和训练建议见 [legit 瞄准数据分析](E:/McEnv/moons/outputs/legit-aim-analysis/README.md)。当前模拟是控制场景，数据集是混合场景，不能直接用总体分位数给两者排名；后续应先按目标误差、距离和动作阶段对齐条件。

## 10. Learned assist 辅助层（2026-09-16 更新）

根据独立 Learned 128 模式的游戏内反馈，现已移除独立模式，改为 Lock / Balance / FullLock 的可选辅助层。保留原模式主体，在包级步长之后应用受限修正；模型预热、关闭、方向冲突或推理异常时使用原模式结果。新增 Learned assist（默认关闭）和 Learned strength（默认 0.35）两个参数，移除上一版四个独立模型转向限制项。

模型继续放在 `lib/aim/`。详细启用步骤、修正预算及限制见 [Learned assist](../lib/aim/README.md)。26.3 分支已升级为正式版，旧 pre / rc 不再支持；前文历史实验表的旧版本号仅记录当时验证环境。
