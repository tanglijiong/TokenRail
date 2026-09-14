# M5.1 本地验收记录 — 2026-09-14

R01–R07 已实现并完成本地验收。M4/M5 基础回归在 PostgreSQL + 四验证者 Besu 上执行；新增可靠性测试使用独立 Anvil 与新 PostgreSQL 数据库。远程 CI 已配置，尚未在本轮运行 GitHub Actions。

## 结果

| 检查 | 结果 |
|---|---|
| Solidity 合约回归 | 45 通过，0 失败 |
| M4WorkflowIT + M5ProjectionIT | 8 通过，0 失败 |
| M51ReliabilityIT | 8 通过，0 失败 |
| Maven 编译 / Flyway V1–V8 | 通过；既有 V1/V2 未改写 |
| Shell 语法与文档差异检查 | 通过 |

基础回归命令会跳过仅限独立环境的 M51ReliabilityIT；独立脚本随后执行该类全部 8 项，不能把前一个命令的 skipped 当成完整验收。摘录见 [test-results.txt](test-results.txt)。

## R01–R07 覆盖

| 任务 | 实测动作与结果 |
|---|---|
| R01 | 领取、签名落库、广播后三个位置由父进程真正强制终止 Java 子进程；重新启动后自动领取过期任务，hash 保持不变，每笔仅一条交易记录及一条经济分录。另验证两 worker 竞争和旧领取版本不能分配 nonce、改签名、释放任务或入账 |
| R02 | 在独立节点执行真正的 `anvil_reset`，保留旧数据库与签名任务，chainId 不变；旧部署停止处理。重新部署后，旧任务领取版本不变，旧 hash 不在交易池或链上；新部署通过 HTTP 转换 11 单位并独立记账，旧 backing 不变 |
| R03 | mint 成功但 backing 事务回滚，报告记录 pendingMints=1000；burn 成功但入账回滚，报告记录 pendingBurns=200；恢复后对旧 H 复核仍 MATCH，并记录 futurePostings=-200。实际破坏 backing 7 单位时出现差异；并发记账与固定 H 读取不误报 |
| R04 | 删除完整持仓快照行可检出；故障重建保留旧 ACTIVE，恢复使用同一版本。另在重建区块事务内真正终止进程，再启动后恢复同一 job/version，backing 不变。并发读取、实时记账、重建以及零金额/自转账通过 |
| R05 | 新高度 MATCH 不关闭旧高度差异；新投影对原 H 复核后，旧差异关联到解决报告。原金额证据保持不变，资产总量也参与检查 |
| R06 | 普通账户不能创建对账或重建任务，也不能读其他钱包；后台任务按创建者限制读取。重复 HTTP key 返回同一 job，参数变化返回 409；任务可查询。模拟工作已提交但任务结果未提交后的重试不会另建投影版本；金额 JSON 使用字符串 |
| R07 | 两组脚本、故障边界、测试入口、CI artifact 上传、迁移说明、API 文档及学习记录已纳入项目；日志只按实际实验类型解释 |

真实进程边界的 operation/hash 与结果分别保存于本目录的 `CLAIMED.evidence`、`SIGNED.evidence`、`BROADCAST.evidence`、`rebuild.evidence`、`reset.evidence`。

## 复现与限制

```bash
scripts/test-m5-projection.sh
scripts/test-m51-reliability.sh
```

独立脚本将运行目录和数据库名打印到终端。不会重置日常 Besu 数据卷；退出时停止本次 Anvil，保留测试数据库供核查。CI 上传测试报告和指定 evidence 文件，不上传私钥。

真实重置测试针对 Anvil，不是 Besu 四验证者数据卷重置。M5 的历史不可用仍有明确的故障注入测试；本轮最初尝试读取运行已久的 Besu 历史状态也遇到节点返回 Internal error，但未把该偶发情况计为可复现的节点裁剪实验。运行时链校验与发交易是独立 RPC，不构成与外部节点重置之间的原子协议。

下一阶段为 M6：业务 API、PaymentEscrow、工作台和演示场景。
