# M5 学习记录：事件投影、区块水位与对账

## 为什么只用 Transfer 改持仓

ERC-20 `Transfer` 是代币余额变化的统一事实。mint 是零地址转入，burn 是转往零地址，DvP 是两个普通转移。如果再根据 `MintConverted`、`BondIssued` 或 `SettlementExecuted` 加减余额，同一资产动作会被计算两次。领域事件只用于解释业务，不参与持仓求和。

## 为什么 checkpoint 必须代表完整区块

一笔 DvP 交易会产生债券和现金两条 `Transfer`。索引器按区块读取日志，并在一个 PostgreSQL 事务中保存原始事件、分录、余额、完整快照和 checkpoint。进程在提交前退出时整个区块回滚，所以数据库不会显示只有一条腿。重启从最后一个已提交区块的下一块继续。

事件唯一键包含 deployment、projectionVersion、blockHash、txHash 和 logIndex。重扫相同区块时原始事件插入被去重，持仓不会再次变化。

## 为什么对账必须固定区块

把数据库区块 H 的余额与链上 latest 比较会把正常索引延迟误报为差异。M5 先固定 active checkpoint 的 blockNumber 和 blockHash，再用区块高度参数调用历史 `balanceOf` 与 `totalSupply`。读取前后核验同高度 blockHash；历史状态不可读时返回 `SNAPSHOT_UNAVAILABLE`，不会退回 latest。

对账结果区分：

- `LAG`：checkpoint 落后于链头。
- `POSITION_MISMATCH`：同一区块的链上余额与投影余额不同。
- `BACKING_MISMATCH`：存款代币 supply 与普通存款 backing 不同。
- `CHAIN_DIVERGENCE`：checkpoint hash 与节点不一致。
- `SNAPSHOT_UNAVAILABLE`：节点不能提供该历史状态。

## 重建能修什么

投影是链上事件的派生数据，可以从部署区块重放到新的 projectionVersion。旧事件、旧差异和处置关联继续保留。普通存款 `deposit_journal` 是经济账务事实，投影重建不重做 conversion 或 redemption 入账，也不自动转账修复链上资产。

同一 chainId 不足以识别一条链。服务同时校验 genesisHash、deploymentId 和 checkpoint blockHash；发现不一致就停止 active projection。新的部署身份使用独立主键空间，不能继承旧投影。

## 本阶段实测

- DvP 两条 Transfer 在故障注入下整块回滚，恢复后同时入账。
- 相同区块重复扫描不增加事件或余额；停扫后可从 checkpoint 补齐。
- 人为损坏 B 的债券投影 10 单位，报告得到 `delta=+10`。
- 索引落后与历史状态不可用分别报告 `LAG`、`SNAPSHOT_UNAVAILABLE`。
- 新 projectionVersion 重建后与链上匹配，旧差异关联为已解决，存款 journal 数量不变。
- checkpoint hash 损坏后索引立即进入 `DIVERGED`；新 deploymentId 不读取旧持仓。
