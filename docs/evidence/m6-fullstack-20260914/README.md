# M6 本地验收记录 — 2026-09-14

环境为 macOS 本机、Java 21、Spring Boot 4.1.1、PostgreSQL 18.6、Foundry 1.7.1，以及 Docker 中四个 Besu 26.8.1 QBFT 验证节点。所有钱包、Bearer token 和资产均为本地合成数据。

## 结果

| 范围 | 结果 | 证据 |
|---|---|---|
| PaymentEscrow | 6/6 通过；fund/release/refund、暂停、冻结、终态互斥、直接捐赠与负债隔离 | `PaymentEscrow.t.sol` |
| 合约全量 | 51/51 通过；格式检查和 `forge lint --deny warnings` 通过 | `npm run check` 与清理缓存后的全量运行 |
| M4–M5 回归 | 8/8 通过 | `M4WorkflowIT`、`M5ProjectionIT` |
| M5.1 回归 | 8/8 通过 | 独立 Anvil、独立 PostgreSQL，R01–R07 |
| M6 T09/T20 | 2/2 通过 | `M6BusinessIT`，真实 HTTP、PostgreSQL、Besu receipt 与日志投影 |
| 四页工作台 | 通过 | 总览、券款交割、条件支付、操作与对账逐页加载；运营员五角色可见；浏览器控制台无错误或警告 |

T20 完成发行、存款转换、两方 allowance、创建/确认/执行 DvP、PaymentEscrow fund/release，并核对链上余额和 PROJECTED 水位。幂等请求复用同一 operationId，改变 payload 返回 409。T09 验证无合规角色返回 403、提前退款链上 REVERTED、到期退款成功和退款后放款 REVERTED。

运行命令：

```bash
cd contracts
npm run check

cd ..
scripts/test-m5-projection.sh
scripts/test-m51-reliability.sh
scripts/test-m6-fullstack.sh
```

## 边界

浏览器验收验证可访问性树、响应式桌面布局、身份切换、真实 API 数据和控制台；没有把截图当作账务或原子性证据。确认员的 release 仍是受信任声明，不证明现实条件。CI workflow 已包含三组 Java 验收，远程 Actions 结果不由本地运行代替。
