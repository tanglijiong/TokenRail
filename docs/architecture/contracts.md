# 智能合约设计

状态：IdentityRegistry、DepositToken、BondToken、DvPSettlement 与 PaymentEscrow 均已实现并通过本地测试。首版不用代理升级；变更代码以新部署和新 deploymentId 表达。

## 1. 共同约束

- 金额采用 uint256 最小单位。DepositToken decimals=2，1,000,000 元为 100000000；BondToken decimals=0，1000 为 1000 单位。API 传十进制整数字符串，禁止浮点。
- 只接受部署清单中固定的两个代币；不支持转账税、rebasing、回调型资产或任意外部 Token。
- 两种 Token 的所有转移路径都校验身份与冻结规则，不能只在 DvP 中检查。OpenZeppelin 5.x 的 ERC20 扩展点为 `_update`；mint 检查接收方，burn 检查来源方，普通转移检查双方。见 [OpenZeppelin ERC20](https://docs.openzeppelin.com/contracts/5.x/api/token/erc20)。
- 管理员、发行员、合规员、投资者、结算提交员分角色。管理员管理角色与暂停权限；不能通过 DvP 绕过双方确认，发行员没有任意扣取投资者代币的权限。
- 使用明确的 custom error 与事件。错误字段包含地址或 ID，不包含私人身份资料。链上失败交易不会留下成功事件。
- 代币全局暂停阻止转移、mint、burn；DvP 暂停阻止创建、确认和交割，但允许取消；准入或暂停变化不会令已有授权永久有效。

## 2. IdentityRegistry

记录 `address → {participantRef, active, frozen, validUntil, canHoldCash, canHoldBond}`。投资者准入条件为 active、未冻结、`block.timestamp < validUntil` 且具有对应资产资格。合规角色通过 `setIdentity(...)` 更新，发出 IdentityUpdated。

合约基础设施单独登记 `systemAccount`，用于 Escrow 持有资金；只能由管理员登记具体部署地址，不能让其绕过全局暂停。DvP 不持有资产，无须代币持仓资格。网络节点许可和持币资格是两套独立约束。

## 3. DepositToken

建议入口：`mintForConversion(conversionId, recipient, amount)`、`requestRedemption(redemptionId, amount)`，另继承 ERC20 transfer / approve / transferFrom。

mint 仅发行角色调用，每个 conversionId 一次，发出 MintConverted(conversionId, recipient, amount)。持币人调用 requestRedemption，原子地记录唯一 redemptionId 并烧毁自己的代币，发出 RedemptionRequested(redemptionId, holder, amount)；发行员不能随意 burn 任意地址。ID 成功使用后不可复用，0 金额拒绝。链上不直接操作链下存款。

发行合约不可能自行证明真实存款存在，因此发行权限是明确的信任边界。教学系统将数据库资金冻结与 conversionId 对应，提供独立支持金额对账。所有 mint / burn 保留 ERC20 Transfer 事件，领域事件用于关联，不重复驱动持仓增减。

## 4. BondToken

单一不可变债券系列：assetRef=ABC2029、发行人、币种、面值最小单位 100000、couponBps=320、到期时间、issuanceCap=100000 单位。发行角色调用 `issue(issueId, recipient, quantity)`，ID 去重且总发行不超过 cap。v0.1 无付息、赎回或强制转移。

报价与面值独立；Golden Demo 使用面值成交仅为简化数据。到期日只展示，不触发自动兑付或拒绝交易；如需到期限制应作为后续明确业务规则加入。

## 5. DvPSettlement

构造时固定 cashToken、bondToken、IdentityRegistry。采用链上双方确认，首版不引入 EIP-712 离线签名。

`createInstruction(id, buyer, bondQuantity, cashAmount, expiry)` 由 seller 本人调用；seller=msg.sender。`confirmInstruction(id)` 仅指定 buyer 可调用。条款创建后不可修改，禁止双方相同、零地址、零金额、已用 ID、过期指令。

两方各自 approve DvP 合约。**ERC20 allowance 只代表代币支出额度，不能代替对某笔交易条款的同意。** 操作员没有修改买方、卖方、价格或收款地址的入口。首版结算入口仅授权结算提交员调用。

| 链上状态 | 进入方式 | 允许动作 |
|---|---|---|
| NONE | 未创建 | seller 创建 |
| CREATED | 创建成功，seller 已确认条款 | buyer 确认；任一交易方取消；到期标记 |
| READY | buyer 确认成功 | execute；任一方取消；到期标记 |
| SETTLED | 两条转移成功 | 无 |
| CANCELLED | 任一方在终态前取消 | 无 |
| EXPIRED | 到期后任意地址调用 expire | 无 |

执行条件始终含 `block.timestamp < expiry`，所以即使没有人主动调用 expire，过期指令也不能结算。取消与执行竞争以链上顺序决定。READY 不代表锁定余额，余额和 allowance 必须在实际执行时重新检查。

`execute(id)` 逻辑规格：

```text
校验调用者、未暂停、READY、未过期
校验双方资格、固定资产、余额、allowance
暂置 SETTLED（checks-effects-interactions + nonReentrant）
safeTransferFrom(seller, buyer, bondQuantity)
safeTransferFrom(buyer, seller, cashAmount)
emit SettlementExecuted(id, seller, buyer, quantities...)
```

任一步失败应向上传播 revert，不捕获转账错误继续执行。两次转移和 SETTLED 状态在一笔 EVM 交易内，要么全提交、要么全回滚。失败 receipt 属于某次提交尝试，不产生链上 FAILED 指令状态；余额补足后可对仍有效的 READY 指令重试。

除普通余额不足外，必须用测试替身让第二条转移在第一条已执行后主动 revert，证明真实回滚，而不仅是预检查拦截。替身不能进入实际部署清单。

关键事件：InstructionCreated（包含完整条款）、InstructionConfirmed、InstructionCancelled、InstructionExpired、SettlementExecuted。其 topic 与 ABI 由编译产物生成，不手写重复维护。

## 6. PaymentEscrow

第五个独立合约。`fund(paymentId, payee, attestor, amount, deadline)` 由付款方调用并把款转入 Escrow，创建 FUNDED 状态；固定唯一 ID、禁止零金额和相同收付款人。双方须合格，attestor 为部署时允许的测试确认员。调用前付款人 approve Escrow。

`release(paymentId)` 仅指定 attestor 可在 `timestamp < deadline` 调用，直接原子改变为 RELEASED 并向 payee 转账。该行为只是测试确认员的链上声明，不证明真实物流事实；不接 Oracle。

`refund(paymentId)` 由 payer 在 `timestamp >= deadline` 调用，原子变为 REFUNDED 并返还付款人。两种终态互斥，所有外部转移使用重入保护。暂停时阻止新 fund 和 release，refund 仍须满足时间与 Token 规则。

收款人冻结导致 release 回滚，仍可在到期后 refund；若付款人也冻结或 Token 全局暂停，退款同样失败，资金保持托管并呈现 HOLD 异常，待恢复资格或解除暂停后再调用。不设置管理员绕过合规的提取通道。

## 7. 必须保持的不变量

| 编号 | 不变量 |
|---|---|
| C01 | 已 SETTLED 指令绝不再次移动资产；成交条款不可变 |
| C02 | DvP 成功时券款各按固定数额转移；失败时四个代币余额和指令状态保持原值 |
| C03 | 普通转移、DvP、Escrow 不改变 totalSupply；mint / burn 与 supply 变化一致 |
| C04 | 未经双方确认及各自 allowance，操作员不能交割 |
| C05 | 所有投资者转移在执行时满足身份资格；直接 transfer 不绕过规则 |
| C06 | mint / issue / redemption 业务 ID 去重；重复广播不重复发行或烧毁 |
| C07 | Escrow 未完成付款金额总和不超过其代币余额；直接捐赠不计入付款负债 |
| C08 | release 与 refund 不能同时成功；未完成付款不能被非授权方提取 |

余额不变仅指两种业务代币。失败交易仍可能消耗 gas 并推进发送者 nonce，不能宣称“交易完全没有任何影响”。
