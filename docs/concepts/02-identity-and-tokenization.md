# M1：身份准入与代币化

状态：已在本地 Foundry 测试中验证，2026-09-13。

## IdentityRegistry 解决什么

许可链允许哪些节点连接，Token 准入决定哪些地址能够持有某种资产，两者不是一回事。M1 的 IdentityRegistry 记录合成 participantRef、激活和冻结状态、有效期，以及现金和债券资格。合规角色维护身份，管理员只管理角色和系统合约资格。

资格在每次实际余额变化时检查。仅在开户或 DvP 入口检查会留下绕过路径：用户仍可直接调用 ERC-20 transfer 或让第三方通过 transferFrom 移动资产。RestrictedERC20 因此覆盖 OpenZeppelin 5.x 的 `_update`，让 mint、burn、transfer 和 transferFrom 共用一条控制路径。

approve 不改变余额，所以允许先授权；到真正 transferFrom 时，发送者和接收者必须仍然有效且未冻结。测试证明失败的 transferFrom 不会扣减余额，也不会消耗 allowance，因为整个调用回滚。

## DepositToken 表达什么

DepositToken 使用两位小数。一枚最小单位代表 0.01 元，因此 1,000,000 元表示为 100000000。它是一个模拟银行存款负债的链上表示，不是无来源的稳定币。

`mintForConversion` 只能由发行角色调用，并要求唯一 conversionId；`requestRedemption` 只能烧毁调用者自己的代币，并要求唯一 redemptionId。业务 ID 在余额操作前写入，但余额操作失败会令同一交易全部回滚，所以失败发行或赎回不会提前消耗 ID。

M1 只实现链上部分。普通存款的 reserve、mint 后的 backing，以及 burn 后的入账恢复要到 M4 的 Java 与数据库工作流实现。

## BondToken 表达什么

BondToken 使用零位小数，每个最小单位就是一个债券单位。ABC2029 的发行人、CNY、面值 100000 分、票息 320 bps、到期日和发行上限在部署时固定。票息和到期日目前只作为元数据，不会自动付息或兑付。

发行角色凭唯一 issueId 配售，totalSupply 永远不能超过 issuanceCap。只有具备债券资格的地址能通过发行或转移获得持仓。报价不属于 Token：M2 的 DvP 指令会单独保存券量与成交总价。

## 与银行系统的对应关系

| 链上概念 | 银行业务近似 | 类比失效处 |
|---|---|---|
| balanceOf | 某地址持仓 | 地址不天然等于法律客户或账户 |
| mint | 存款转换 / 债券配售入账 | 合约不能自行证明链下资金或法律发行事实 |
| burn | 赎回请求中的链上注销 | 普通存款入账仍是另一个系统的步骤 |
| AccessControl role | 岗位权限 | 本地管理员不是多机构治理或生产密钥控制 |
| paused / frozen | 全局停办 / 单户冻结 | 真实处置还需审批、原因和法律流程 |
| bytes32 business ID | 银行流水幂等键 | 生成、映射和跨系统恢复尚需 Java 工作流 |

## 已验证的失败场景

- 非合规角色不能登记身份，非管理员不能登记系统合约。
- 未登记、过期、冻结或缺少对应资产资格的地址不能持有资产。
- 普通转移与 transferFrom 不能绕过准入；失败不改变余额或 allowance。
- 非发行角色不能 mint / issue；零 ID、零金额、重复 ID 和超 cap 都被拒绝。
- 暂停时 mint、burn 与 transfer 都走同一余额更新门禁。

## M2 需要回答

- allowance 为什么只表示支出额度，不能表示对某笔交易价格的确认？
- 卖方创建、买方确认后，结算操作员还能改变哪些内容？
- READY 状态为何不代表余额已锁定？
- 第一条转移成功、第二条转移 revert 时，EVM 怎样恢复两个 Token 和 DvP 状态？
