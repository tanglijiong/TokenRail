# M0 本地验证证据

执行时间：2026-09-13（Asia/Dhaka）。代码工作区：TokenRail。该报告使用合成数据和临时 Anvil 链；每次重启链后交易 hash 可重复或变化，不能作为公共链证明。

## 工具版本

| 工具 | 实测版本 |
|---|---|
| macOS | 15.5 arm64 |
| Node.js | v25.8.1 |
| npm | 11.11.0 |
| Forge / Cast / Anvil | 1.7.1，项目本地锁定 |
| Solidity compiler | 0.8.30，foundry.toml 锁定 |
| EVM target | Cancun，foundry.toml 锁定 |
| Java | OpenJDK 21.0.12.1 |
| Maven | 3.9.9 |
| Docker CLI | 26.1.4 |

Foundry v1.8.1 官方发布包直连在本机下载时持续无响应，未安装不完整包。改用 Foundry 官方 npm 包 1.7.1，并通过 package-lock.json 固定完整依赖树。

## 合约验证

`npm run check` 实际执行格式检查、编译和测试：

```text
Solc 0.8.30 compiler run successful
StateTransitionLab runtime size: 724 bytes
5 tests passed; 0 failed; 0 skipped
```

覆盖：成功状态提交、超过 limit 回滚、写入后主动回滚、未授权调用回滚、构造失败。

## 临时链验证

`scripts/m0-anvil-transaction.sh` 实际输出的关键结果：

```text
chainId: 31337
signer: 每次运行在进程内生成，地址已在实际输出中核对
native transfer status: 0x1
sender nonce: 0 -> 1
recipient wei: 0 -> 1

StateTransitionLab: 每次运行新部署
successful call status: 0x1
counter: 10 -> 25
intentional revert status: 0x0
revert gasUsed: 0x67f0
revert nonce: 3 -> 4
counter after revert: 25
```

地址和交易 hash 没有写入固定验收断言，因为脚本使用一次性密钥并在每次运行部署新合约。私钥只存在于脚本进程内，不进入输出、文件或版本库。脚本验证 receipt 字段、nonce 和余额 / storage 后以非零退出码报告失败。

## 当前结论与限制

M0 在本机完成：工具版本受控、最小合约可构建、5 个测试通过、真实签名交易成功，真实 status=0x0 交易证明 storage 回滚而 nonce / gas 仍有影响。

这不是 Besu、多节点、业务代币或 DvP 验收。CI 工作流已建立但只有推送到 GitHub 后才会产生远端运行记录；本报告只声明本地结果。
