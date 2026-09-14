# M6 业务 API 与工作台

M6 把既有合约能力接入统一的持久化工作流。业务写请求需要 Bearer 演示身份和 `Idempotency-Key`，先保存 resource、command、operation 与 outbox，再由 worker 分配 nonce、签名、广播和核对 receipt。相同身份、路由和 key 的相同请求返回原 operation；payload 改变时返回 409。

`INVESTOR_A` 与 `INVESTOR_B` 可提交自身 allowance，并分别按条款参与 DvP 或 PaymentEscrow。`OPERATOR` 在本地演示配置中同时拥有 `ISSUER`、`OPERATOR`、`COMPLIANCE`、`ATTESTOR`、`RECONCILER`，用于发行、执行结算、更新身份、确认放款和发起对账。服务仍以调用者自己的测试密钥签名；角色不允许代替交易方授权。

资源查询同时返回三类证据：`operationStatus` 表示持久化任务进展，`transactionHash/minedBlock/minedHash` 表示链上 receipt，`indexedBlock/indexedHash` 表示事件投影水位。`syncStatus` 依次为 `NOT_SUBMITTED`、`OPERATION_*`、`CHAIN_CONFIRMED` 或 `PROJECTED`。HTTP 202 不表示链上成功，REVERTED 也不会被页面包装成成功。

工作台由 Spring Boot 直接托管，无额外前端构建步骤：

- 总览：网络、投影水位、身份、持仓和部署合约。
- 券款交割：发行、双方 allowance、创建、确认、执行、取消和状态查询。
- 条件支付：Escrow allowance、fund、release、refund 和 HOLD 说明。
- 操作与对账：按身份查看 operation/receipt，并提交持久化对账或重建 job。

本地运行 `scripts/test-m6-fullstack.sh`。脚本重新部署五个合约，在四节点 Besu 和 PostgreSQL 上只运行 `M6BusinessIT`，避免链重置类测试共享状态。页面可在启动服务后访问 `http://127.0.0.1:8080/`。
