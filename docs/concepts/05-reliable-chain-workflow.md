# M4 学习记录：可靠链交易与模拟存款

## 为什么 HTTP 超时不能记为失败

客户端、Java 服务、RPC 节点和共识网络之间存在多个独立故障点。服务没有收到广播响应，只能说明响应未知；节点可能已经收到交易并最终出块。此时重新生成一笔新 nonce 的 mint 可能造成重复发行。

TokenRail 在广播前保存签名后的完整交易和本地计算的 hash。恢复时先查该 hash 的 receipt；没有 receipt 时重播相同字节。相同字节具有相同 hash 和 nonce，合约的 conversionId / redemptionId 又提供业务层去重，因此至少一次发送不会变成至少一次资产动作。

## nonce 为什么需要数据库串行化

节点的 pending nonce 只反映节点已经看见的交易，不包含应用刚分配、尚未广播的交易。并发请求若都直接读取 pending nonce，会签出互相覆盖的交易。服务按 `(deploymentId, signer)` 锁住 `signer_nonce` 行，并取节点 pending nonce 与数据库 next nonce 的较大值；交易表再用 `(deploymentId, signer, nonce)` 唯一约束兜底。

## 链和数据库为何不能放进一个事务

PostgreSQL 事务不能原子提交 EVM 交易。把 RPC 调用包在数据库事务中只会长时间持锁，仍无法解决进程在两边提交之间崩溃的问题。M4 将流程拆为可恢复状态：先 reserve，后 mint，再将 reserve 转为 backing；赎回先 burn，确认后 credit。

`BURN_CONFIRMED` 很关键。它表示代币已经不可逆地烧毁，但普通存款尚未入账。数据库恢复只重试 credit，不再 burn。`deposit_journal(operationId, postingKind)` 的唯一约束让入账恰好一次。

## 本阶段实测

- 相同幂等 key 和 payload 返回同一 operation；payload 改变返回冲突。
- 16 个并发发行任务为同一 signer 分配连续且无重复的 nonce。
- 广播成功后主动丢弃响应，记录变为 `UNKNOWN`，恢复后只发生一次 mint。
- mint receipt 成功后模拟数据库回滚，reserved 保留；重试后只生成一条 backing 分录。
- burn 成功后模拟 credit 回滚，状态保留在 `BURN_CONFIRMED`；重试后只入账一次。
- Java 通过真实 Besu JSON-RPC 完成身份、债券发行、双方 approve、DvP execute、存款赎回。
