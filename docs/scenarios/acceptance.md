# 演示与验收规格

状态：M0–M7 已完成本地验收。M7 从干净 git archive 重跑全部发布门禁，三个对外场景已通过真实 Besu、PostgreSQL、HTTP 与浏览器完成录屏。具体范围见 [M7 证据](../evidence/m7-release-20260914/README.md)。

## 1. Golden Demo：Atomic DvP

固定合成数据：ABC2029 为模拟债券系列；现金 decimals=2，券 decimals=0。初始 B 有 1,500,000 元普通模拟存款，全部转换成 150000000 现金最小单位；普通可用存款降为 0。A 获配 1000 单位债券。

交易：A 卖给 B 1000 单位，约定总价 100000000 最小单位，截止时间为初始化时当前链时间加 1 小时。展示交易标识 TR202609130001，但每次环境使用独立部署身份。A 创建、B 确认，双方各 approve 精确数额，operator 执行。

| 持有人 | 债券之前 | 债券之后 | 现金之前（元） | 现金之后（元） |
|---|---:|---:|---:|---:|
| A | 1000 | 0 | 0 | 1,000,000 |
| B | 0 | 1000 | 1,500,000 | 500,000 |

证据：同一个 txHash 下两条正确的 Transfer 和一条 SettlementExecuted，receipt status=1；双方确认在先；totalSupply 不变；链下投影完成后在同一个区块四个余额均匹配。展示约 3 分钟：业务 30 秒、双方授权 30 秒、成交与余额 40 秒、失败回滚 40 秒、对账 40 秒；时长是脚本目标。

## 2. 三个对外场景

| 场景 | 步骤 | 必须观察到 |
|---|---|---|
| A 条件支付 | P 预存 1000 元到 Escrow，测试确认员 release；另一笔到期 refund | 确认前收款人未收到，release 后收到；独立超时案例仅 refund 成功 |
| B DvP | Golden Demo；另初始化 B 仅有 900,000 元后尝试同样成交 | 成功时双腿完成；不足时拒绝且四个代币余额不变 |
| C 合规拒绝 | B 确认并 approve 后冻结 B，再 execute；再尝试普通 transfer | 两条路径均拒绝；解冻后仍有效的 READY 指令可重试 |

demo-only 故障入口必须只在本地演示 profile 注册。对账案例通过故意令链下 B 债券投影变为 990、链上仍为 1000，产生 delta=+10；重建投影后回到 1000，旧差异保留处置轨迹。不改链上资产。

## 3. 测试与证据矩阵

| ID | 层 | 场景 | 验收 |
|---|---|---|---|
| T01 | Foundry | 正常 DvP | C01–C04 成立，精确余额与事件 |
| T02 | Foundry | 第二腿主动 revert | 第一腿、指令状态、allowance 消耗与事件均回滚 |
| T03 | Foundry | 余额 / allowance 不足 | 分类清晰，不转移资产 |
| T04 | Foundry | 无 buyer 确认、非参与方确认、篡改 ID / 条款 | 无权方不能成交 |
| T05 | Foundry | 重复 execute、重复 mint / issue / burn ID | 不重复资产动作 |
| T06 | Foundry | 过期边界：expiry-1、expiry、expiry+1 | 仅边界前可 execute；取消与终态互斥 |
| T07 | Foundry | 确认后冻结、资格到期、直接 transfer / transferFrom | 均在执行时检查准入 |
| T08 | Foundry fuzz / invariant | 随机合法与非法动作序列 | C01–C08；包含成功操作计数，避免全 revert 的空洞测试 |
| T09 | Foundry | Escrow 未授权 release、提前 refund、重复 release、冻结退款 | 无错误付款；HOLD 资金留存 |
| T10 | Java + PostgreSQL | 相同 key 重复与冲突 payload | 同请求同 operation，不同请求 409 |
| T11 | Java + 本地链 | 广播后丢响应、广播前后进程崩溃 | hash 查证或同字节重播，单次业务生效 |
| T12 | Java + PostgreSQL | 同 signer 并发 | nonce 唯一，队列恢复，不覆盖他人交易 |
| T13 | 索引集成 | 事件重复、区块事务中崩溃、索引离线再启动 | 无漏记、无重复；双腿不可部分投影 |
| T14 | 对账集成 | 同区块投影损坏、投影滞后、历史读取不可用 | 分别报差异、LAG、SNAPSHOT_UNAVAILABLE |
| T15 | 集成 | mint 成功数据库宕机；burn 成功入账宕机 | 恢复后恰好一次记账，reserved 不提前释放 |
| T16 | 集成 | 重建代币投影 | 不重做普通存款赎回入账；报告可复现 |
| T17 | Besu 四节点 | 停一个 / 停两个验证节点再恢复 | 一个时继续；两个时停块；不把 pending 判失败 |
| T18 | 网络恢复 | checkpoint hash 不符、同 chainId 重置 | 停止投影；新 deployment 隔离旧数据 |
| T19 | Besu + Java | 完整发行、确认、授权、DvP、赎回 | 使用真实 JSON-RPC / receipt / logs，不用 mock 冒充 |
| T20 | 全栈 | 三个场景、重启、对账修复 | 全流程可复现，页面无虚构成功状态 |

M2 结果：T01–T07 已由单元测试覆盖；T08 已覆盖 DvP 适用的 C01–C06，并以 256 组随机金额和 3 条各 64×32 次调用的不变量运行验证。T02 除合约内状态断言外，还通过真实 Anvil `status=0x0` receipt 验证零持久化日志、余额、allowance 和 READY 状态全部回滚。详见 [M2 本地证据](../evidence/m2-dvp-20260913/README.md)。

M3 结果：T17 已在四验证者 Besu 26.8.1 网络完成；停一个验证者仍出块，停两个后区块高度稳定，重启全部验证者后恢复。四个业务合约已按 Cancun 字节码部署并写入 manifest，但 T19 的 Java、完整发行、双方操作和赎回仍属于 M4。详见 [M3 本地证据](../evidence/m3-besu-20260913/README.md)。

M4 结果：T10–T12、T15、T19 已在 Java 21、PostgreSQL 18.6 和真实四节点 Besu 上完成。覆盖幂等冲突、16 路并发 nonce、广播后响应丢失、mint/burn 后数据库回滚，以及身份、发行、双方授权、DvP、赎回完整路径。详见 [M4 本地证据](../evidence/m4-java-20260914/README.md)。

M5 结果：T13、T14、T16、T18 已在真实 PostgreSQL 与 Besu 上完成。覆盖 DvP 双腿区块事务、日志去重与断点续扫、投影差异/滞后/历史不可用分类、新版本重建、普通存款 journal 隔离、checkpoint 分叉停止及 deploymentId 隔离。详见 [M5 本地证据](../evidence/m5-projection-20260914/README.md)。

M6 结果：T09 的 6 个 PaymentEscrow 合约测试覆盖 C07–C08、权限、到期和 HOLD；T20 的 2 个全栈测试覆盖业务幂等、角色拒绝、发行、转换、allowance、DvP、Escrow、receipt 和投影同步。四页工作台已用真实浏览器逐页检查，身份切换后角色正确且控制台无错误。详见 [M6 本地证据](../evidence/m6-fullstack-20260914/README.md)。

M7 结果：场景 A 的 release 和到期 refund 分别进入 RELEASED/REFUNDED 且投影完成；场景 B 的成功 DvP 进入 SETTLED，资金不足失败前后四个余额完全一致；场景 C 在冻结时的显式链上 transfer receipt 为 `0x0`，解冻后原 READY 指令进入 SETTLED。详见 [M7 本地证据](../evidence/m7-release-20260914/README.md)。

资金不足的快速演示可能被 gas estimate 提前拦截，应标记为 PRECHECK_REJECTED。另用测试工具显式发送给定 gas limit 的无效交易取得 status=0 receipt；不将“未广播的预检拒绝”伪装为链上 revert。T02 的主动第二腿失败在合约测试中必须覆盖。

## 4. 证据文件与发布门槛

每次验证计划输出 `docs/evidence/<runId>/`：commit、时间、机器与工具版本、部署 manifest、执行命令、测试报告、交易 receipt、余额快照、对账报告、失败原因。全部用合成身份；私钥与认证令牌不进入证据。

CI 门禁：合约构建 / 单测 / fuzz / invariant，Slither 高严重性发现须修复或逐条解释并复核，Java 单测与 PostgreSQL / Anvil 集成，Web build。四节点 Besu 可在专用 workflow 运行，但发布必须有实际运行通过的报告；环境不可用记 BLOCKED，不记 PASS。

发布还需干净环境启动、三场景实际运行、演示录屏、README 与实现一致、确定许可证、未提交敏感信息。单测覆盖率不是原子性或资金正确性的替代证据。
