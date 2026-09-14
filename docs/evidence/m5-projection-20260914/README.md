# M5 投影与对账验收证据

日期：2026-09-14（Asia/Dhaka）

环境：Java 21.0.12.1、Spring Boot 4.1.1、Web3j 5.0.2、PostgreSQL 18.6、Besu 26.8.1 四验证者 QBFT。

执行：

```bash
scripts/test-m5-projection.sh
```

测试连接真实 PostgreSQL 与 Besu JSON-RPC，按真实区块读取 logs、blockHash 和历史合约状态。

| 验收项 | 结果 | 观察 |
|---|---|---|
| T13 | PASS | 区块提交前故障使 DvP 双腿与 checkpoint 一起回滚；恢复后双腿同时出现；重复扫描不重复记账；离线后补齐 |
| T14 | PASS | 投影损坏产生 `POSITION_MISMATCH` 和 `delta=+10`；滞后与历史不可用分别分类 |
| T16 | PASS | 新 projectionVersion 重放后恢复链上余额；普通存款 conversion/redemption journal 数量不变；旧差异保留处置关联 |
| T18 | PASS | checkpoint hash 不符将 projection 标为 `DIVERGED` 并停止；相同 chainId 下的新 deploymentId 使用独立数据空间 |

M5 的 4 个集成测试与 M4 的 4 个回归测试在同一轮运行，共 8 个测试全部通过。运行生成物、数据库数据和测试密钥仅位于被忽略的 `deploy/.runtime/`。
