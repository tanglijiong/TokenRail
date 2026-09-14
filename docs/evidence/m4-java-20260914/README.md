# M4 Java 与模拟存款验收证据

日期：2026-09-14（Asia/Dhaka）

环境：Java 21.0.12.1、Spring Boot 4.1.1、Web3j 5.0.2、PostgreSQL 18.6、Besu 26.8.1、四验证者 QBFT，Solidity 0.8.30。

执行：

```bash
scripts/test-m4-java.sh
```

结果：Maven `BUILD SUCCESS`，4 个集成场景全部通过，最终一键复验测试时间 28.10 秒。测试连接真实 PostgreSQL 与 Besu，不使用链或数据库 mock。

| 验收项 | 结果 | 观察 |
|---|---|---|
| T10 | PASS | 幂等重放返回原 operation；冲突 payload 被拒绝；余额只 reserve 一次 |
| T11 | PASS | 广播后注入响应丢失，持久化为 UNKNOWN；按 hash 恢复并仅 mint 一次 |
| T12 | PASS | 同一 signer 的 16 个并发任务获得连续、唯一 nonce |
| T15 | PASS | mint 后数据库回滚及 burn 后 credit 回滚均可恢复；journal 各一条 |
| T19 | PASS | Java 完成身份登记、债券发行、双方授权、原子 DvP、赎回并核对余额 |

另以真实 HTTP 调用 `POST /api/v1/deposit-conversions`，收到 202/RESERVED；随后查询 operation 得到 `COMPLETED` 与 Besu txHash `0x3fe01b4b619013cb56e56bb86844d190f6e8cae30d489b918b942c6f28818010`。

本地生成的 deployment manifest、钱包私钥、签名原文和数据库内容位于被忽略的 `deploy/.runtime/`，不写入证据或版本库。该结果证明本地实验流程可恢复，不代表真实银行账务或法律最终性。
