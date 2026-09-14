# M3 Besu 本地验证证据

执行时间：2026-09-13（Asia/Dhaka）。验证对象为四节点 Besu QBFT 网络、节点与账户许可、Cancun 兼容性和四个业务合约部署；不包含 Java、PostgreSQL、投影或完整业务初始化。

## 1. 固定配置

```text
Besu:          26.8.1, linux-aarch_64, OpenJDK 25
Docker:        26.1.4
Docker Compose:2.39.2-desktop.1
chainId:       20260913
validators:    4
block period:  2 seconds
round timeout: 4 seconds
EVM milestone: Cancun from block 0
```

镜像固定为 `hyperledger/besu:26.8.1@sha256:6f3f21ce533383fcc8db3bce02252b59d5a9e776b72b5a1c8ecd2db011600042`。运行时查询得到 4 个验证者，节点 1 有 3 个 peers，节点和账户 allowlist 分别为 4 项和 1 项。随机未许可发送者被节点以 `Sender account not authorized` 拒绝。

## 2. 故障与恢复

从仓库根目录执行：

```bash
scripts/test-m3-besu.sh
```

验收观察：

```text
all validators:         blocks advance
one validator down:     8 -> 10
two validators down:    12 -> 12 (stalled)
all validators restart: recovered at 13
```

脚本在停两节点后先等待网络进入 round change，再在独立观察窗口比较区块高度，避免把在途区块误判为继续出块。恢复时重启全部本地验证者，使递增的 QBFT round timeout 回到初始值。

## 3. 合约与 manifest

IdentityRegistry、DepositToken、BondToken 和 DvPSettlement 均使用 Solidity 0.8.30 / Cancun 编译产物部署成功。脚本检查四个地址均有运行时代码，并读取 DepositToken `decimals() == 2`。

每次全新运行生成 `deploy/.runtime/besu/deployment-manifest.json`，包含 deploymentId、chainId、genesisHash、部署区块、部署账户，以及每个合约的地址、部署交易、部署区块、ABI hash 和 bytecode hash。密钥及节点数据位于被 Git 忽略的 runtime 目录。

## 4. 结论与限制

M3 在本机完成：四验证者 QBFT、静态节点许可、发送账户许可、故障 quorum、恢复流程和当前合约的 Besu / Cancun 兼容性均已实际验证。

这些容器共享一台主机，只能模拟验证者进程故障。远端 workflow 需推送后才会产生独立 CI 记录；真实多主机网络、TLS、生产密钥管理、Java 交易可靠提交和完整 T19 仍未验收。
