# TokenRail 四节点 Besu 网络

本目录保存可复现的 QBFT 配置模板；运行中生成的 genesis、验证者密钥、一次性部署账户、节点数据和部署 manifest 位于 `deploy/.runtime/besu/`，不会进入版本库。

## 运行

需要 Docker、Node.js 20+、jq，以及已在 `contracts/` 执行过 `npm ci`。

```bash
# 生成全新网络材料并启动四个验证者
scripts/besu-network.sh up

# 查看容器状态
scripts/besu-network.sh status

# 向当前网络部署 IdentityRegistry、DepositToken、BondToken、DvPSettlement
scripts/deploy-m3-contracts.sh

# 从空网络运行完整 M3 验收
scripts/test-m3-besu.sh

# 停止但保留数据，或删除全部运行数据
scripts/besu-network.sh down
scripts/besu-network.sh reset
```

RPC 地址为 `http://127.0.0.1:8545`；其他验证者的诊断 RPC 分别绑定本机 8546、8547、8548。链 ID 为 20260913，区块周期 2 秒，基础 round timeout 为 4 秒。

## 配置边界

- Besu 镜像固定为 26.8.1 的多架构 manifest digest；每个节点 JVM 上限为 512 MiB。
- 四个节点使用静态地址和本地节点 allowlist，节点发现关闭；节点 1 正常应看到 3 个 peers。
- 账户 allowlist 初始只包含本次运行生成的部署账户。验收会证明未列入的发送者被 RPC 拒绝。
- Token 的 IdentityRegistry 准入是资产业务规则，与 Besu 账户许可不同；后续 Java 参与方要显式加入网络账户许可和合约身份登记。
- 本机端口绑定不是生产级网络隔离。固定容器 IP、一次性明文密钥和单机四容器只适用于本地学习环境。

每次 `reset` 后都会生成新的 genesis hash 和 deploymentId。Java 与投影层必须读取当前 `deployment-manifest.json`，不能凭相同 chain ID 沿用旧地址或游标。
