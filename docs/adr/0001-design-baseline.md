# ADR-0001：TokenRail 首版设计基线

状态：已采用；Solidity / Foundry / OpenZeppelin 与 Besu 网络决策已完成运行验证。日期：2026-09-13。

## 背景

目标是基于银行业务经验学习 EVM / Solidity，构建小型可复现实验。仓库原本只有标题 README，无应用代码和既有技术约束。设计优先覆盖资产授权、同链原子性、异步失败与对账。

## 决策与取舍

| 决策 | 原因 | 代价 / 后续条件 |
|---|---|---|
| 单链、单行存款、单系列债券 | 把首版集中在交割正确性 | 不声称跨行清算或法律最终性 |
| 四核心合约 + 后半程 Escrow | 保留原规划三场景 | 最终五业务合约，明确修正原数量约束 |
| Foundry / Anvil 先行，Besu 四节点必验 | 快速掌握合约再处理网络 | Anvil 通过不能替代 Besu 验收 |
| Java 21、Spring Boot、Web3j | 延续用户企业经验并连接 EVM | 具体组合需 M0 / M4 构建实测 |
| 一个 Java 应用、逻辑包隔离 | 降低模块和运维开销 | 不模拟微服务分布式事务 |
| ERC20 受限转移，OpenZeppelin 5.x | 成熟基础上学习权限与资产表示 | 不宣称兼容完整 ERC-3643 |
| 链上 create + confirm | 双方对完整条款的授权可检查 | 多几笔交易，首版暂不优化签名成本 |
| 非托管 DvP，执行时 pull 双腿 | 不引入额外资金锁定与解锁生命周期 | READY 不保证余额可用，成交可能失败 |
| 固定 Token 与非升级合约 | 缩小外部合约与治理风险面 | 升级需要新部署与显式数据隔离 |
| DB outbox + 链上 ID + 事件去重 | 恢复超时和重复交付 | 无跨系统原子提交，必须对账 |
| 同区块余额对账 | 避免拿旧投影对最新链头产生假差异 | 节点须支持所选高度读取 |
| 演示签名器由服务持有测试密钥 | 无需安装钱包即可复现银行操作流程 | 只是托管模拟，不代表独立机构授权部署 |

## 需要在实现时验证，不能提前宣称已解决

- Solidity 0.8.30、OpenZeppelin 5.6.1、Foundry 1.7.1 已由 foundry.toml 与 package-lock.json 固定。Besu 26.8.1 已按多架构 manifest digest 固定并完成四节点验收。Spring Boot、Web3j 和 PostgreSQL 版本仍待对应里程碑验证；禁止运行时使用漂移的 latest。
- Java 21 与所选 Spring Boot / Web3j 的依赖和生成 wrapper 编译兼容性。
- Solidity `evm_version=cancun` 与 Besu genesis 从区块 0 激活 Cancun，四个业务合约已实际部署并读取成功。
- 当前机器已用每节点 512 MiB JVM 上限运行四节点；加入数据库后的整体资源开销仍需 M4 实测。
- Besu 历史状态读取能力及测试窗口；无法读取时对账必须显式阻断。
- 开源许可证及各依赖许可证兼容性在发布阶段确定；当前不添加不确定的法律声明。

## 安全与故障边界

需要验证的攻击面是：操作员冒充投资者、allowance 被错误视为交易同意、重放业务 ID、合规检查绕过、第二腿失败、重入、整数与单位错误、事件重放、nonce 冲突、过早释放存款 reserve。

控制方案详见合约和系统设计。链上准入只有合成规则；许可链参与者可能读取全网余额和条款；管理员与发行员仍为受信角色。本地测试不覆盖 HSM、多组织治理、法律权属、真实 KYC 和生产灾备。

## 核验过的官方参考

以下参考用于原理和 API 选择，不将历史对话的新闻判断作为项目成立条件。

- [BIS：未来货币系统蓝图（2023）](https://www.bis.org/publications/aer-2023/blueprint-future-monetary-system-improving-old-enabling-new)：代币化货币与资产放入可编程平台的研究背景；本项目只是缩小后的教学模型。
- [OpenZeppelin ERC20 5.x](https://docs.openzeppelin.com/contracts/5.x/api/token/erc20)：ERC20、SafeERC20 与 `_update` 扩展点；具体版本仍需锁定。
- [Solidity 安全说明](https://docs.soliditylang.org/en/latest/security-considerations.html)：外部调用与重入风险，采用 checks-effects-interactions 并测试失败路径。
- [Foundry 工具说明](https://www.getfoundry.sh/)：Forge 用于合约构建测试，Anvil 用于本地链，Cast 用于手动调用实验。
- [Besu QBFT](https://docs.besu-eth.org/private-networks/how-to/configure/consensus/qbft)：验证者共识与节点失效导致的停块条件。
- [Web3j 合约交互](https://docs.web3j.io/4.14.0/smart_contracts/interacting_with_smart_contract/)：Java 合约包装器、调用与交易交互；链接版本不是本项目依赖已经锁定的声明。
