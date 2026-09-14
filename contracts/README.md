# TokenRail Contracts

当前已完成 M0–M6。M0 的 StateTransitionLab 只用于 EVM 学习；M1 提供身份和资产基础，M2 提供同链原子 DvP，M6 加入条件托管支付。部署脚本会将五个业务合约部署到 Cancun 激活的 Besu QBFT 网络。

| 合约 | 已实现行为 |
|---|---|
| IdentityRegistry | 合规角色登记身份、冻结、有效期、现金 / 债券资格；管理员登记已部署的系统合约 |
| RestrictedERC20 | OpenZeppelin ERC-20 统一准入检查、暂停与角色基础 |
| DepositToken | 2 位小数、转换 ID 幂等 mint、持有人赎回 burn |
| BondToken | 0 位小数、固定 ABC2029 元数据、发行 ID 幂等、总发行上限 |
| DvPSettlement | 卖方创建、买方确认、双方取消、到期终结、授权操作员原子交割 |
| PaymentEscrow | 付款人注资、指定确认员截止前放款、付款人到期退款、HOLD 与负债跟踪 |

金额均为最小单位：100.00 元写作 `10000`，1000 单位债券写作 `1000`。participantRef、conversionId、redemptionId、issueId、instructionId 和 paymentId 为 bytes32，Java API 接受 `0x` 加 64 位十六进制字符。

本地验证：

```bash
npm ci
npm run check
```

`npm run check` 依次执行格式门禁、编译与体积报告、零警告 lint、单元测试、256 组金额 fuzz 和状态不变量测试。Foundry 1.7.1 对部分测试发现错误可能仍返回成功退出码，因此仓库的测试包装脚本同时检查失败摘要。

若要检查真实失败交易的 receipt 和链上回滚，从仓库根目录运行：

```bash
scripts/m2-anvil-atomicity.sh
```

当前信任边界：

- 合规角色能决定合成身份资格；这不是外部 KYC 证明。
- 发行角色能根据链下 conversionId 铸造存款代币；支持资金账本在 M4 实现。
- 管理员能授予或撤销角色、暂停 Token，并登记系统合约。
- 结算操作员只能执行双方已经确认的固定条款，不能替交易方确认或修改条款。
- 测试确认员只能在付款截止前 release；该调用不证明现实条件已经满足，管理员不能提取托管款。
- 任何 ERC-20 balance 变化都通过同一 `_update` 准入路径；approve 本身不移动资产，实际 transferFrom 时重新检查。

READY 不锁定资产。执行时仍要重新检查双方资格、余额和 allowance；任一腿失败会让整笔交易 revert。当前范围只证明一条 EVM 链内的技术原子性，不包含链下账务最终性或跨链结算。
