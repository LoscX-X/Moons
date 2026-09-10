# Blink 与 LagUtils 开发入口

## 已合并的公共能力

所有实现位于 `src/minecraft/shared/java/com/blanoir/moons/client/management/network`。

| 入口 | 用途 |
|---|---|
| `LagUtils<T>` | 有界 FIFO、原对象保存、按时间到期、按数量释放、按匹配包释放前缀、队龄、快照 |
| `PacketBlink` | Minecraft 包的手动缓冲；调用者决定发送或接收回放 |
| `PacketDelayQueue` | 绑定连接和世界的出站 Blink / 定时延迟，统一回放保护 |
| `LagPacketPolicy` | 交互包前释放、受击/纠正时释放、换世界/断线时丢弃的共用判断 |
| `EntityLag` / `TrackedEntityPosition` | 单实体入站缓冲、服务器坐标解码和渲染插值 |

`PacketBlink`、`PacketDelayQueue`、Backtrack 的专用队列现在共用 `LagUtils` 存储与时间逻辑。
Reach 和 EntityLag 通过原有的 `PacketBlink` API 使用公共缓冲。
三个 FakeLag 模式继续保留 Constant / LowHealth / Random 的配置和触发判断，
共用回放保护、包策略、连接与世界身份检查；新模块无需再列举其他 FakeLag 模式的 `isReplaying()`。

## 出站接入

每个使用者拥有一个实例，不共享可写的全局包列表。现有 FakeLag 由模式选择器保证互斥。
多个独立模块若同时启用，仍按 SEND PRE 监听顺序由第一个取消事件的模块持有该包；
公共缓冲不替模块选择优先级，也不实现跨模块混合延迟叠加。

```java
private static final PacketDelayQueue packets = new PacketDelayQueue(512);

// SEND PRE：在模块自己的锁内进行观察、策略判断和入队。
if (LagUtils.isReplaying()) return;
packets.observe(event.connection(), client.level);
if (!enabled || LagPacketPolicy.mustFlushBefore(event.packet())) {
    packets.flush();
    return;
}
if (packets.offer(event.packet())) { // Blink：手动持有
    event.cancel();
} else {
    packets.flush(); // 队列满时先释放前面的包，再让当前包走原路径
}
```

定时延迟使用 `packets.offer(packet, delayMillis)`，每个 Tick 调用
`packets.observeClient(client)` 和 `packets.flushDue()`。无新包的 Tick 也要推进到期释放。
手动 Blink 不会被 `flushDue()` 释放，需显式 `flush()` 或 `flush(count)`。

生命周期处理：

- 每个 Tick 观察当前连接与世界，防止没有新发送时继续保存旧会话数据。
- 收到 `mustDiscardOnIncoming(packet)` 为真的包，先 `discard()` 再重置模块状态。
- 对同一世界内的纠正、受击等，按 `mustFlushOnIncoming(client, packet)` 释放并重置。
- 设置变更或普通禁用时用 `flushClient(client)` 再核对当前会话并释放；模块卸载或世界失效时 `discard()`。
- Bundle 沿现有版本适配器展开，逐个子包检查，避免父包和子包重复处理。

连接/世界通过对象身份比较，不使用地址字符串或实体编号代替会话身份。
`clear()` 仅清空缓冲；`discard()` 还解除连接和世界引用。
FakeLag 卸载清理由 `FeatureBootstrap.shutdown()` 调用 `FakeLag.discardPending()` 完成。

## 入站与分段 Blink

`PacketBlink.offer(packet)` 成功后才取消原事件，满队列返回 false，不静默吞掉当前包。
`drain()` 返回原包对象且顺序不变；`drain(count)` 释放固定包数；
`drainThrough(count, predicate)` 释放到第 count 个匹配包为止，包含中间的非匹配包。
例如按移动包释放时，不应单独挑出移动包而让它越过之前的攻击/交互包。
count 为 0 不释放；匹配数量不足则释放现有整个前缀；负数抛出异常。
`snapshot()` 返回不可修改的列表副本，包对象本身仍是原对象，不应并发修改。

`PacketBlink` 不拥有连接或 listener。调用者必须保留并验证入队时的 listener、世界和目标身份。
入站回放维持当前项目的客户端线程和 APPLY 边界，不通过出站 `Connection.send` 发送。
Backtrack 保留自己的目标快照和回放回调，仅合并其底层 FIFO。

## 时间与回放契约

- 新功能使用 `LagUtils.nowMillis()` 的单调时钟；三个 FakeLag 模式的计时已统一使用它。
- `LagUtils.offer(value, now, delayMillis)` 允许显式时钟；Backtrack 保留其现有毫秒时间轴。
  同一个队列的入队、到期和队龄计算必须使用相同时间轴。
- 后来的短延迟不能越过先前的长延迟；到期的一批包一次释放，不额外逐包节流。
- 容量检查与入队原子化；null 和满队列返回 false。默认没有无限队列。
- `LagUtils.replay(action)` 的保护仅作用于当前线程，并在嵌套调用和异常后恢复。
  自定义出站缓冲也应在捕获前检查 `LagUtils.isReplaying()`。
- 正常发送事件仍会触发，旋转历史、发送完成观察不丢失。不要替换成全局 `sendNoEvent`。
- 发送抛异常时，已经尝试发送的包不会重试，尚未尝试的尾部仍保留；调用者处理连接失败时应丢弃尾部。
  这不表示服务器确认收到了已尝试发送的包。

## 参考来源与适配范围

本轮阅读的本地参考包括：

- Myau `management/BlinkManager.java`：持有原包、所属模块、按移动包计数。
- Myau `management/LagManager.java`：按年龄延迟释放、回放保护和断线清理。
- LiquidBounce `features/blink/BlinkManager.kt`：统一 Blink 缓冲、按移动包释放前缀、入站/出站边界。
- OpenZen `manager/LagManager.java`：按时间维护延迟队列。

参考根目录为 `E:\McEnv\skid`。此次按 Moons 类型化事件和版本边界重新实现公共机制，
没有搬入旧版 Minecraft 包类型、全局可变队列、渲染模块或注解事件总线。
没有增加新的可开关 Blink 模块；这些接口用于后续功能接入。

## 验证

```powershell
.\gradlew.bat verifyNetworkLag verifyBacktrack verifySourceLayout
.\gradlew.bat compileAllVersions
```

`verifyNetworkLag` 覆盖延迟顺序、容量、包对象身份、分段释放、嵌套回放、发送异常、
跨连接/世界丢弃、回放中切换会话以及并发入队。此验证不代表游戏内命中率或服务器接收确认。
