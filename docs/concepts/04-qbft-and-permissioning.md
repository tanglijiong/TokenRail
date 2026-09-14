# M3：QBFT、许可与部署身份

## 1. 四个验证者为何只能容忍一个退出

QBFT 需要超过三分之二的验证者参与才能提交区块。四个验证者在线时，提交门槛为三个：停一个后还有三个，网络继续出块；停两个后只剩两个，无法形成提交 quorum，区块高度停止增长。

停块不表示某笔业务失败。尚未取得 receipt 的交易应显示网络停滞或状态未知，不能直接创建新业务 ID 重试。验证者恢复后，应用继续按原 transaction hash 查询结果。

QBFT 在连续失败轮次中会增加等待时间。本地故障验收重启全部验证者以重置 round timeout，然后确认区块高度继续增长。

## 2. 三层许可解决不同问题

| 层 | 控制对象 | 本阶段实现 |
|---|---|---|
| 节点许可 | 哪些 enode 能组成 P2P 网络 | 四个生成的验证者 enode 静态 allowlist |
| 账户许可 | 哪些交易发送者能通过节点提交交易 | 本次运行的一次性部署账户 allowlist |
| Token 准入 | 哪些地址能持有现金或债券 | IdentityRegistry 的业务规则 |

节点能连入网络不代表其账户可以持币；账户可提交交易也不代表它具备现金或债券资格。M3 实际查询了两种 Besu allowlist，并让一个未列入的随机发送者得到 `Sender account not authorized`。M1 的直接 transfer 和 transferFrom 测试继续保证 Token 准入不能被普通 ERC-20 路径绕过。

## 3. Genesis 与 EVM 版本

Solidity 编译目标固定为 Cancun，因此 Besu genesis 从区块 0 激活 Cancun。只让节点启动不足以证明兼容：M3 把 IdentityRegistry、DepositToken、BondToken 和 DvPSettlement 的同一份编译产物实际部署到 Besu，并读取合约代码和 DepositToken decimals。

镜像使用版本标签和 digest 双重固定。标签便于人读，digest 防止同名镜像内容漂移。genesis 使用零 base fee 和本地预分配测试余额；原生币只支付 gas，不充当 DvP 的现金腿。

## 4. chainId 不能识别一次链实例

重置网络后可以继续使用相同 chainId，但 genesis hash、验证者密钥、部署交易和合约地址都会变化。只用 chainId 关联数据库投影，可能把旧链事件和新链状态混在一起。

部署脚本因此生成 deploymentId，并记录 genesis hash、部署区块、每个合约地址、交易 hash、ABI hash、运行时代码 hash以及工具版本。下游数据必须按 deploymentId 隔离。

## 5. 本次边界

四个容器仍运行在一台电脑上，不构成独立故障域，也没有生产密钥管理、TLS、多组织治理或隐私交易。M3 证明的是协议行为、许可配置和合约兼容性。Java 的签名、nonce、超时恢复和数据库一致性从 M4 开始验证。
