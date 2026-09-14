# M1 合约本地验证证据

执行时间：2026-09-13（Asia/Dhaka）。验证对象为 IdentityRegistry、RestrictedERC20、DepositToken 和 BondToken；不包含 DvP、Java 或 Besu。

## 固定依赖

| 依赖 | 版本 |
|---|---|
| Solidity | 0.8.30 |
| EVM target | Cancun |
| OpenZeppelin Contracts | 5.6.1 |
| Forge / Cast / Anvil | 1.7.1 |

版本由 foundry.toml、package.json 和 package-lock.json 固定。

## 质量门禁

`npm run check` 的实际结果：

```text
format: PASS
compile: PASS
forge lint --deny warnings: PASS
npm audit (official registry): 0 vulnerabilities

IdentityRegistryTest: 5 passed
DepositTokenTest: 10 passed
BondTokenTest: 7 passed
StateTransitionLabTest: 5 passed

Total: 27 passed, 0 failed, 0 skipped
```

编译后的生产合约 runtime size：

| 合约 | 字节 |
|---|---:|
| IdentityRegistry | 3,192 |
| DepositToken | 4,235 |
| BondToken | 4,525 |

## 关键验证结论

- 身份冻结、过期、资产资格分别生效；系统账户只能由管理员为已部署合约启用。
- DepositToken decimals=2，mint、transfer、burn 与 totalSupply 的精确余额已验证。
- BondToken decimals=0，元数据、发行上限和持仓已验证。
- transfer 与 transferFrom 均不能绕过准入；失败 transferFrom 不消耗 allowance。
- conversion、redemption 和 issue ID 不能重放；因准入或冻结失败时 ID 不会被提前消耗。
- 非发行、非合规、非管理员调用以及暂停状态均被拒绝。

## 限制

这些是进程内 Foundry 合约测试。M1 尚未声明真实银行存款 backing、Java 事件消费、Besu 多节点部署或 DvP 原子性已经完成。远端 GitHub Actions 仍需推送后才能产生独立 CI 记录。
