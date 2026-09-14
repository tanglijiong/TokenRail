# TokenRail Java 服务

M4–M6 服务采用 Java 21、Spring Boot 4.1.1、Web3j 5.0.2、Flyway 和 PostgreSQL 18.6，实现模拟存款转换与赎回、DvP 与 Escrow 业务编排、Transfer 事件投影、历史快照和同区块对账。

## 运行

需要 Docker、Java 21、Maven、Node.js 20+ 和 jq。首次运行：

```bash
cd contracts
npm ci
cd ..

scripts/m4-environment.sh
set -a
source deploy/.runtime/m4/server.env
set +a
mvn -f apps/server/pom.xml spring-boot:run
```

环境脚本启动四节点 Besu 和 PostgreSQL、部署合约、生成两个本地测试钱包并登记身份。私钥和环境文件仅写入被忽略的 `deploy/.runtime/`。

完整验收：

```bash
scripts/test-m4-java.sh

# 包含 M4 回归以及 M5 投影、对账、重建验收
scripts/test-m5-projection.sh

# 独立环境中的真实崩溃、链重置与完整账务验证
scripts/test-m51-reliability.sh

# M6 业务 API、PaymentEscrow 与页面状态验收
scripts/test-m6-fullstack.sh

# M7 干净源码发布验收
scripts/test-m7-release.sh

# M7 三场景可交互演示（端口 18081）
scripts/demo-m7-scenarios.sh --keep-running

# 生成真实浏览器录屏与场景证据
scripts/record-m7-demo.sh
```

## API

演示身份使用 `Authorization: Bearer investor-b-token`。提交新业务或管理任务必须带 `Idempotency-Key`，返回 202；存款结果通过 operation 查询，管理结果通过 job 查询。默认演示账户不是对账员。

```bash
curl -X POST http://127.0.0.1:8080/api/v1/deposit-conversions \
  -H 'Authorization: Bearer investor-b-token' \
  -H 'Idempotency-Key: demo-conversion-1' \
  -H 'Content-Type: application/json' \
  -d '{"businessId":"0x1111111111111111111111111111111111111111111111111111111111111111","amount":"150000000"}'

curl http://127.0.0.1:8080/api/v1/operations/<operationId> \
  -H 'Authorization: Bearer investor-b-token'
```

存款接口为 `POST /api/v1/deposit-conversions`、`POST /api/v1/redemptions` 和 `GET /api/v1/operations/{operationId}`。

M5.1 查询与管理接口（对账/重建写接口返回 202 job）：

- `GET /api/v1/positions?wallet=0x...`：返回 active projectionVersion 的持仓和完整区块水位。
- `POST /api/v1/reconciliations`：在当前完整 checkpoint 上执行历史 `balanceOf` / `totalSupply` 对账。
- `GET /api/v1/reconciliations/{id}`：读取差异、区块和处置状态。
- `POST /api/v1/projections/rebuild`：创建新 projectionVersion 并从部署区块重放。

M6 业务接口：

- `GET /api/v1/session`、`GET /api/v1/operations`：读取当前身份、角色、部署边界和操作证据。
- `POST/GET /api/v1/allowances`、`POST /api/v1/bond-issues`：提交授权和债券发行。
- `POST /api/v1/settlements` 及 `confirm/execute/cancel`：编排 DvP；`GET /settlements/{id}` 返回条款、链上状态和同步水位。
- `POST /api/v1/payments` 及 `release/refund`：编排 Escrow；`GET /payments/{id}` 返回托管状态和 receipt 证据。
- `POST/GET /api/v1/identities/{wallet}/status`：合规员更新、任意已认证演示身份读取链上准入。

启动服务后访问 `http://127.0.0.1:8080/`。四页工作台使用 `investor-a-token`、`investor-b-token` 和 `operator-token` 切换本地合成身份；所有写操作的 202 状态只表示已入队。

## 可靠性边界

服务先在数据库事务中保存业务操作与 outbox，再在事务外访问 RPC。每个 signer 的 nonce 在数据库行锁下分配；签名原文和 hash 在广播前持久化。RPC 超时或进程中断保留 `UNKNOWN`，恢复时先按 hash 查询 receipt，仍未知才重播完全相同的签名字节。

转换状态为 `RESERVED → MINT_PENDING → COMPLETED`。赎回状态为 `BURN_PENDING → BURN_CONFIRMED → CREDITED`。链上已经成功但数据库提交失败时，worker 从中间状态继续，`deposit_journal` 的唯一约束保证普通存款只记一次。

索引器只消费两个代币的 ERC-20 `Transfer`。每个完整区块的原始日志、持仓分录、余额、快照和 checkpoint 在同一个数据库事务提交。重扫通过事件唯一键去重；checkpoint hash 不符时 projection 进入 `DIVERGED` 并停止。重建只生成新的代币投影版本，不重放 `deposit_journal`。

M5.1 使用固定数据库快照、历史 receipt 和在途金额还原 H 的 backing。工作流通过带版本的租约恢复，重建在 BUILDING 版本完成后原子切换；对账/重建要求对账员角色与幂等键，返回可查询的持久化 job。金额响应统一为十进制字符串。完整接口、角色配置、迁移和边界见 [M5.1 运行约定](../../docs/m51-api-and-operations.md)。

真实 Java 进程终止及独立 Anvil 链重置已有测试；历史状态不可用仍有明确标注的故障注入测试，未进行 Besu 数据卷重置验收。
