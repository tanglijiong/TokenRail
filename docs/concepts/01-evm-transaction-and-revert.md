# M0：EVM 交易、签名与回滚

状态：已在本地 Foundry / Anvil 实验中验证，2026-09-13。

## 我如何理解这条链路

私钥不等于账户密码。它用于对一份确定的交易内容签名；节点从签名恢复发送者，从而确认谁授权了这次状态变化。地址由公钥推导，公开地址不会赋予签名能力。

一笔状态交易依次经历：

```text
构造交易（to、value/data、nonce、gas、chainId）
  → 私钥签名
  → 广播原始交易
  → 节点执行 EVM
  → 区块收录
  → receipt（status、block、gasUsed、logs）
```

`eth_call` 只在指定状态上模拟执行并返回结果，不进入区块，也不消耗 nonce 或 gas。发送交易会改变链状态；HTTP 请求成功或拿到 txHash 只表示节点接收了请求，必须继续检查 receipt 和业务事件。

## TokenRail 的实际证据

`scripts/m0-anvil-transaction.sh` 在临时 chainId 31337 上完成两组实验：

1. 进程内生成一次性测试私钥，由 Anvil 本地测试 RPC 注入 gas 资金，再推导地址、签名并发送 1 wei。成功 receipt 的 status=0x1；发送者 nonce 从 0 变为 1，接收方余额从 0 变为 1。
2. 部署 StateTransitionLab。成功交易令 counter 从 10 变为 25。随后显式给定 gas limit，广播一个“先写 storage 再 revert”的交易；receipt status=0x0，counter 仍是 25，但 nonce 从 3 变为 4，交易也消耗了 gas。

这说明 revert 会撤销当前交易内 EVM 状态写入和事件，却不会让已经被区块处理的交易消失。发送者仍为计算付费，nonce 也已消费。这正是后续 DvP 第二腿失败时“两种业务代币余额回滚”的基础，同时也是系统不能把失败描述为“什么都没发生”的原因。

## 最小合约中的边界

StateTransitionLab 的 owner 是部署交易的发送者，而不是某个 Java 用户名。只有 owner 能增加 counter；超过 limit、未授权调用和故意失败都会 revert。它用 custom error 表达错误、event 表达已提交的变化。

合约目前只是 M0 实验，不进入 TokenRail 业务部署。它没有代理升级、复杂外部调用或资产价值，因此不能代表 DvP 已实现，也不能替代后续的重入、授权和不变量测试。

## 与传统 Java 的类比及失效处

EVM revert 类似数据库事务回滚：同一笔调用中的状态变化不会部分提交。但链不是由 Spring 管理的本地数据库事务。应用提交交易后，HTTP 超时不能证明链上回滚；链上交易也不能原子提交 PostgreSQL。因此后续 Java 层必须保存业务 ID、txHash 和恢复状态，并用事件投影与对账衔接。

## 我仍需在后续阶段回答

- EIP-1559 交易与本次 legacy 交易在 gas 字段上有何差异？
- 合约调用发生嵌套时，子调用 revert 被捕获和未捕获分别怎样影响全局状态？
- Besu QBFT 的区块最终性如何改变 receipt 确认策略？
- allowance、双方交易确认与实际私钥授权为何是三件不同的事？
