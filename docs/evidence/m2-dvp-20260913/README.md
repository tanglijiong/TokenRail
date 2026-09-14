# M2 DvP 本地验证证据

执行时间：2026-09-13（Asia/Dhaka）。验证对象为 DvPSettlement、现有身份与代币合约，以及只用于测试的第二腿失败替身；不包含 Java、Besu、PaymentEscrow 或链下投影。

## 1. 构建与自动化测试

从 `contracts/` 执行：

```bash
npm run check
```

结果：

```text
Solidity 0.8.30 compilation: successful
forge lint --deny warnings: passed
DvPSettlement runtime size: 5,283 bytes
Total: 45 passed, 0 failed, 0 skipped
DvP unit/fuzz tests: 15 passed; fuzz runs: 256
DvP invariants: 3 passed; each 64 runs × 32 calls
```

覆盖正常交割、固定条款、双方确认、角色限制、余额和 allowance、冻结后重试、暂停、双方取消、到期边界、ID 与 execute 重放，以及第二腿失败后的状态回滚。测试 handler 记录真实成功次数和非法终态动作成功次数，避免只靠全部 revert 产生空洞不变量结果。

## 2. 真实失败 receipt

从仓库根目录执行：

```bash
scripts/m2-anvil-atomicity.sh
```

脚本启动临时 Anvil，部署包含失败替身的测试 harness，并强制广播一笔现金腿 revert 的 execute 交易。实际检查结果：

```text
receipt status:       0x0
receipt logs:         0
gasUsed:              0x1f0e1
seller bond:          1000 -> 1000
buyer bond:           0 -> 0
seller bond allowance:1000 -> 1000
buyer cash:           100000000 -> 100000000
instruction state:    2 -> 2 (READY)
```

这证明第一腿已进入调用路径后，第二腿 revert 会撤销债券余额变化、allowance 消耗、SETTLED 状态和所有日志。失败交易本身仍消耗 gas。部署密钥在进程内临时生成，不写入文件或输出。

## 3. 结论与限制

M2 在本机完成：双方对固定条款链上确认，授权操作员可在一笔交易内完成券款交换；取消、到期、暂停和重放边界已有测试；第二腿失败的真实 receipt 证明业务状态原子回滚。

该结论只适用于当前固定的标准 ERC-20 资产和单条本地 EVM 链。远端 CI 需推送后才会产生独立记录；四节点 Besu、Java 签名与重试、数据库投影和对账将在后续阶段验证。
