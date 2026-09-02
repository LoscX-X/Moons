# Anti-cheat compatibility notes

本文档记录必须保留的反作弊兼容边界。这里的结论来自
`build/matrix-decompiled` 中的 Matrix 反编译源码；修改对应代码前必须同时检查
本文档和代码旁的 `MATRIX-SPECIFIC` 注释。

## Matrix / KILLAURA / acceleration

- 服务端提示：`KILLAURA(vl3)`，说明文本包含 `aimbot // acceleration`；截图中的
  `kaabtacl` 对应 `ka.abt.acl`。
- Matrix 配置项：`modules.aimbot.check_aimbot_acceleration`。
- Matrix 位置：
  - `build/matrix-decompiled/me/rerere/matrix/internal/en.java` 的 `ia(...)`
    （约第 59 行，字节码 `0x1e0..0x37f`）。
  - `en.loadConfig()` 约第 1149 行；解密后静态字段 `b` 对应
    `check_aimbot_acceleration`。
  - `build/matrix-decompiled/me/rerere/matrix/internal/mk.java` 的 `uh`、`sI`、
    `hI` 保存 20 个样本并计算均值及离散度。

Matrix 对每个服务端旋转包先计算绝对 yaw/pitch 增量，再计算：

```text
yawAcceleration   = abs(currentYawDelta   - previousYawDelta)
pitchAcceleration = abs(currentPitchDelta - previousPitchDelta)
```

两个长度为 20 的窗口填满、处于攻击后的检查窗口且当前 yaw 增量至少约 1.5°时，
它检查低平均加速度与 `yaw` 低离散度、`pitch` 高离散度的组合。`mk.hI()` 实际返回
的是未除以样本数的平方差之和再开方；连续异常会增加 `en.o` 缓冲，超过 8 后上报。

本次回归的直接原因是
`src/shared-client/java/com/blanoir/moons/client/utils/rotation/aim/HumanAimSimulator.java`
曾把 pitch 包域加速度从原先约 `1.05..2.10` 整体翻倍为 `2.10..4.20`。目标跳跃时，
20 包窗口里的 pitch 二阶差分离散度因此越过 Matrix 的 `5.0` 阈值。

### 对应补丁与不可修改位置

- `HumanAimSimulator.java:10-43`：
  `MATRIX_MAX_PACKET_PITCH_ACCELERATION = 2.10`，并恢复两种模式的安全包域包络。
  **不得用“加快上下索敌”为理由提高该上限。**
- `PacketRotationSmoother.java` 的 pitch `acceleratedAxisStep(...)` 调用处：
  **不得绕过、二次放大或在量化后直接写入目标 pitch。**
- `src/versions/26.1/.../SilentAuraRotationController.java` 和
  `src/versions/26.2/.../SilentAuraRotationController.java` 的
  `stabilizeAimHeight(...)`（约第 585 行）：上下索敌速度在渲染域通过目标高度跟随
  和预测解决；当前 `stickyAimY += box.minY - lastTargetMinY` 会立即跟随目标箱体，
  最终发包仍必须经过共享 smoother。

该补丁由共享的 `HumanAimSimulator` / `PacketRotationSmoother` 同时覆盖 Minecraft
26.1.2 和 26.2；两个版本的 `SilentAuraRotationController` 保持同一约束。

### SilentAura PLAYER_UPDATE 时序（既定设计，不得修改）

`SilentAuraRuntime.attackRotation(...)` 必须使用本 tick 由
`packetRotation()` 生成并缓存的候选旋转。`TriggerBot` 在 `PLAYER_UPDATE` 中用这组
旋转完成本地射线判定并触发攻击，随后同一 tick 的 `sendPosition`/移动包 hook 继续
复用完全相同的缓存候选。这个先后关系是 SilentAura 的既定设计：

```text
PLAYER_UPDATE：生成/复用本 tick packetRotation -> 射线判定 -> 触发攻击
sendPosition：复用同一候选并发布本 tick 静默旋转
```

**不得**把攻击射线改为 `sentRotation()`（上一 tick 已确认的旋转），也不得把攻击
整体延迟到移动包 `PACKET_SEND_POST`。前者会让攻击判定固定落后一 tick，目标或玩家
跳跃时反而更容易空刀；后者会改变原版攻击、Critical、冷却和 Matrix swing 守卫之间
的调用顺序。`packetRotation()` 的 tick cache 正是保证攻击路径和随后移动包使用同一
组 yaw/pitch 的机制。

以后排查跳跃空刀应优先检查瞄准点、碰撞箱安全内缩、垂直预测与射线余量，
不再反复改为“上一包旋转”或“攻击延后一拍”。

### 穿体后远落点卡刀修复

空中瞄准增强加入 `applyAirbornePitchInertia(...)` 后，穿过目标身体并在另一侧落得较远
时可能出现攻击暂时停顿。这里不是服务端空刀，也不是 Center/Closest 选点错误：眼睛
离开目标碰撞箱后，yaw 尚有接近 180° 的回转，同时空中 pitch corridor 仍可能保持
穿体前的 pitch，`TriggerBot` 因射线暂时不相交而正确地不触发攻击。

对应补丁位于两个版本的 `SilentAuraRotationController`：

- `crossingLookaheadTicks(...)` 只在 `crossingTarget` 期间依据双方相对水平速度预估
  穿出侧。Crossing 的 X/Z 仍要求本地眼睛水平中心真正进入目标箱；Y 不得再要求眼睛
  本身位于目标箱内，而应要求本地身体箱与目标箱仍有垂直交叠。普通跳跃时眼睛会高于
  玩家箱，但双方身体仍在穿越；若继续用完整 `AABB.contains(eye)`，空中路径永远不会
  提前回 yaw，这正是“平移正常、水平跳穿卡刀”的原因。
- 平地且眼睛确实位于目标箱内时保留原有 2-5° crossing pitch。跳跃穿身且眼睛高于
  目标箱时，用 Center/Closest 已选定的同一个 `point.y` 和预测水平出口准备 pitch；
  不得改写瞄准点的 Y 策略。
- 退出 `crossingTarget` 后设置两 tick 的 `crossingRecoveryUntilTick`；这两 tick
  `applyAirbornePitchInertia(...)` 直接释放到普通几何 pitch，使远落点立即恢复射线。
- 该补丁**不得**通过提高 `PacketRotationSmoother` 的转角、pitch 加速度或跳过 GCD
  量化来加速。穿体预转仍走既有 packet smoother，Matrix 的 yaw snap 边界和
  `2.10` pitch 二阶差分上限保持不变。

## Grim / 禁止攻击前额外 PosRot

2026-09-02 曾仿照 LiquidBounce KillAura `ON_TICK`，在 SilentAura 攻击前额外发送
`PosRot(current xyz + cached attack rotation)`。该方案已经实服否决并完整撤销，不能
重新加入：

- Grim `PacketOrderO` 要求支持 `CLIENT_TICK_END` 的客户端在 movement/flying 包后、
  tick-end 前不得再发送普通同步包。攻击前 `PosRot` 会把顺序变成
  `flying -> ANIMATION -> INTERACT_ENTITY`，因此两种包都会上报。
- 同一 tick 稍后的原版 `sendPosition` 还会再发送相同 position/look，触发
  `AimDuplicateLook`、`BadPacketsV(delta=0)`，并进一步污染 `Timer`、`TickTimer`、
  `Post` 与 `Simulation` 的 tick 计数/预测。
- **不得**在 `PLAYER_UPDATE` 的攻击前显式发送 `Rot`、`Pos` 或 `PosRot`；也不得在
  原版 `sendPosition` 之外制造第二个 movement/flying 包。LiquidBounce 的
  `ON_TICK` 是可选瞬时旋转模式，不适合直接移植到当前连续 SilentAura。

Matrix 偶发 HITBOX 必须继续从穿体边界、服务端可见射线余量和目标快照差异排查，
不能再通过增加攻击前 movement 包处理。Center/Closest 的眼高规则继续保留。

## 攻击挥手包顺序

Moons 不再为 Matrix `CLICK/check_swing` 全局补发攻击前挥手包。所有手动和自动攻击
继续通过原版 `Minecraft.startAttack()` 分发，线上顺序保持 `ATTACK -> SWING`。
禁止在全局 `PACKET_SEND_PRE` 中为每个攻击插入额外 `ServerboundSwingPacket`；
这会把纯手动攻击也改成 `SWING -> ATTACK -> SWING`。

## 版本验证

- Minecraft 26.1.2：`compileClientJava` 通过。
- Minecraft 26.2：`compileClientJava` 通过。
- 以上是源码、字节码和编译验证；最终 Matrix 服务器回归测试应分别覆盖：
  - 静止目标与水平绕圈；
  - 对手连续跳跃/下落时 Lock 与 Balance；
  - SilentAura 连续攻击；
  - Reach/FakeLag 开启时的攻击与 swing 顺序。

## SilentAura debugger / 攻击门诊断

开启 `silentaura.debugger` 后，HUD 会分别显示瞄准层与攻击门状态：

- `candidate`：本 tick 候选静默旋转对当前目标的本地射线结果。
- `sent`：最近一次已经进入移动包的静默旋转对当前目标的射线结果。
- `gate`：TriggerBot 最终放行原因。`aim` / `blocked` / `range` 属于瞄准或几何问题；
  `charge` 属于冷却；`critical wait` / `critical block` 明确表示 Critical 拦截；
  `attack` 表示已经交给原版攻击分发。
- `critical ... aimingWindow` 与 `decision`：显示当前 Critical 模式、是否处在已预约的
  瞄准窗口，以及预测的攻击种类、剩余 tick 和原因。

Predict Critical 在预测到本次跳跃稍后存在合法暴击点时允许返回 `WAIT`，在不安全落地段
允许返回 `BLOCK`；Packet Critical 开启但当前状态不满足时也允许返回 `BLOCK`。这些是
Critical 自己的攻击门，不得误判为 SilentAura 射线卡住。调试器只读取状态和执行本地
射线，不调用 `gateAutomaticAttack(...)`，因此不会创建、刷新或取消暴击预约。

## SilentAura Matrix compatibility / 专用旋转档案

`silentaura.matrix` 是显式兼容开关，默认关闭：

- 开启时必须继续使用 `HumanAimSimulator.samplePacketMotion(...)` 的 Matrix 包级曲线；
  pitch 二阶加速度不得超过 `2.10`，穿身状态连续包 yaw 步长不得超过 `18` 度。
- 关闭时使用独立的 Generic 响应曲线。Generic 可以提高 Lock/Balance 的追赶速度，
  但不得反向修改、复用或提高上面的 Matrix 常量。
- 两个档案都必须保留鼠标 GCD 量化与最终真实 `EntityRayState.HIT` 攻击门；开关不能
  变成跳过射线或允许 `AIM` 攻击。

`silentaura.aimMode=full_lock` 是独立的 Myau 风格档案：

- 使用碰撞箱水平中心和 `5%..75%` 高度走廊；包级角步为
  `fullLock.angleStep ± 5`，并使用 Myau 的随机 smoothing 缩放和 `1` 度死区；
- 默认 `angleStep=90`、`smoothing=0`，不经过 Lock/Balance 的加速度包络、预测、
  wander、jitter 或穿身限步；
- 因此 FULL-Lock 下 `silentaura.matrix` 必须视为无效，不能把该档案描述为 Matrix
  compatible；切回 Lock/Balance 后原开关值继续生效；
- 仍必须保留本客户端的灵敏度 GCD、同 tick 候选旋转以及最终真实 `HIT` 攻击门。
- 本地第三人称 `AvatarRenderState` 必须按 `partialTick` 在前后两个 FULL-Lock 目标角之间
  插值；该值只用于模型头/身体显示，不得反向写入包旋转、移动修正或攻击射线。

Lock 的攻击范围内点选择会以最近一次真实发送的旋转作为参考：现有射线仍命中时将
交点向 Center/Closest 的策略点内收并保持；射线为 `AIM` 时选择角距离最小的可见箱面，
同时保留 Center 的视线高度或 Closest 的 Y 策略。这是点选择/追赶优化，不得通过提高
Matrix 包级加速度来代替。

## SilentAura / Critical 联动开关

`silentaura.critical` 只决定 SilentAura 的 `SILENT_RAY` 是否进入 Critical，默认开启：

- 开启时，SilentAura 只有在本 tick 的最终真实射线为 `HIT` 后才进入
  `Critical.gateAutomaticAttack(...)`。Predict 可以 `WAIT/BLOCK`，Packet 可以在真实
  攻击钩子里发送其微小下落包。
- 关闭时，SilentAura 跳过 Critical 的自动攻击门，并在本次同步攻击分发期间跳过
  `Critical.beforeAttack(...)`；这一步必须同时绕过，否则 Packet 模式仍会从全局真实
  攻击钩子插入下落包。
- 关闭只影响 `RayEntry.SILENT_RAY`。普通相机 TriggerBot、手动攻击和 Critical 模块
  自身继续维持原行为；关闭开关时会清除 SilentAura 已有的自动 Critical 预约。
- Critical 不接管 SilentAura 的瞄准。`AIM` 时不会发出攻击；同一目标的短暂 `AIM`
  只拒绝本 tick，并保留尚未执行的自动 Critical 预约。下一次射线重新成为 `HIT` 后
  才允许继续经过攻击门。短暂 `AIM` 本身不应产生攻击包，不能用“攻击后把视线交给
  Critical”解释检测上报。只有目标、距离、遮挡或模块状态真正失效时才清除预约。

该开关不得改变 SilentAura 的同 tick `PLAYER_UPDATE` 时序、最终 `HIT` 射线门、冷却门、
GCD 量化或 Matrix 旋转档案。它用于隔离 Critical 的等待/位移路径，而不是允许 AIM 攻击。

## TriggerBot + Predict 旧版提前预约契约（不可回退）

该行为依据最近一次包含完整战斗源码的 Git 提交 `6f704f60` 恢复。旧版
TriggerBot + Predict 接近“刀刀暴击”的关键不是放宽暴击判定，而是 **Predict 在攻击
蓄力达到随机阈值前就开始工作**：

1. TriggerBot 每 tick 计算当前扩展蓄力和本次随机阈值之间还差多少 tick：
   `ticksUntilReady = ceil((requiredCharge - currentCharge) * attackDelay)`。
2. 在普通蓄力门之前调用
   `Critical.gateAutomaticAttack(client, target, ticksUntilReady)`，让 Predict 将武器冷却与
   当前跳跃的下降暴击窗口提前对齐。
3. 当前门控结果必须逐项映射旧版 `Critical.requestAttack(...)`：`NONE -> ALLOW`、
   `DEFERRED -> WAIT`、`ATTACKED -> ATTACK`、`ABORTED -> BLOCK`。其中 `ATTACK` 表示
   Predict 已经决定本 tick 出刀，TriggerBot 只负责复用当前最终射线并同步分发攻击；它不是
   一个还要重新经过随机蓄力门的普通 `ALLOW`。
4. 如果决策器返回本 tick 的真实 `CRITICAL + 0t + vanilla-critical-now`，或者一个旧版
   `DEFERRED` 预约在本 tick 完成并映射为 `ATTACK`，必须优先于 TriggerBot 的 `0.7-1.3`
   随机过充阈值；其原版暴击强度门仍为 `> 0.90`。只有没有被 Predict 接管的普通
   `NONE/ALLOW` 攻击继续满足本次随机蓄力阈值。
5. Critical 关闭、SilentAura 的 Critical 联动关闭、非玩家目标或当前模式不是 Predict 时，
   必须返回普通 `ALLOW`，不得产生 `ATTACK` 旁路。

### 不可修改的边界

- 不得重新改成“先等蓄力达到阈值，再以 `earliestAttackTick = 0` 询问 Predict”；这会错过
  当前跳跃的下降窗口，是本次退化的直接原因。
- `DEFERRED` 创建后，`remainingTicks` 与 `earliestAttackTicks` 必须像旧版一样每客户端 tick
  各自只递减一次；不得按新预测结果滚动刷新期限。预测滑出原跳跃、期限耗尽或变为
  `no-decision` 时，只能清除预约并拒绝当前 tick，不能临时退化成一刀普通攻击。
- 只恢复旧版的**提前预测编排**，不恢复旧版相机射线分发。普通 TriggerBot 继续使用相机
  `hitResult`；SilentAura 继续使用本 tick 将发布的 packet rotation 做最终真实
  `EntityRayState.HIT` 验证，并由 TriggerBot 同步分发攻击。
- Predict 的 `WAIT/BLOCK` 不能自行绕过射线发包；`AIM`、`BLOCKED`、超距、错误目标时
  仍不得攻击。即使决策为 `vanilla-critical-now`，没有最终 `HIT` 也不能出刀。
- 同一 SilentAura 目标的一帧 `AIM` 只表示 packet ray 暂时没有穿过目标箱，不得清除
  已存在的 Predict 自动预约；本 tick 仍直接拒绝攻击，恢复 `HIT` 后才继续执行预约。
  `no target`、`range`、`blocked`、`target moved`、关闭/释放 SilentAura 等真实失效必须
  继续取消预约，防止旧目标或失效几何状态被复用。
- 不得为了恢复旧版手感增加一 tick 等待、在攻击前显式发送 `Rot/Pos/PosRot`，或让
  Critical 绕过 `CombatInputController` 独立发送攻击。攻击与随后原版 `sendPosition`
  必须继续复用同一个 `PLAYER_UPDATE` 候选旋转。
- GCD 量化、Matrix/Generic 旋转档案以及 `silentaura.critical` 开关边界保持不变。
