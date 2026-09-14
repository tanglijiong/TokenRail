# M5.1 接口与运行约定

## 迁移与身份

V1/V2 保持不变。V3–V8 新增任务租约、部署账户、交易目标绑定、对账证据、完整区块历史和后台任务。旧银行表保留为迁移前记录；业务转为使用 `deployment_account` / `deployment_backing`。若旧数据库有多个 deployment 且存在未归属的共享银行余额，V4 明确拒绝迁移，须先人工确定余额归属，不能自动复制资金。

账户身份仍由配置和本地签名文件注册。默认账户角色是 `ACCOUNT`；可通过 `tokenrail.reconciler-participants` 显式列出已注册的对账员 ID。撤权应修改对应 deployment 的 `deployment_participant.role`。不提供公开角色提升接口。

故障注入、改账与清库辅助方法只允许显式 `local-demo` 或 `reliability-test` profile；普通运行不启用这两个 profile。

## HTTP 契约

所有业务接口使用 `Authorization: Bearer …`。金额使用最小单位，响应中的任意精度整数以十进制字符串输出。持仓响应带 deploymentId、projectionVersion、blockNumber、blockHash；操作响应带 deploymentId。

| 方法与路径 | 身份 | 行为 |
|---|---|---|
| GET `/api/v1/positions?wallet=…` | 账户本人 / 对账员 | 账户默认仅看本人钱包，指定他人钱包返回 403；对账员可查看当前部署 |
| POST `/api/v1/reconciliations?block=H` | 对账员 | 必须提供 `Idempotency-Key`；202 返回持久化任务。省略 H 时固定提交时的索引高度 |
| POST `/api/v1/projections/rebuild` | 对账员 | 必须提供 `Idempotency-Key`；202 返回任务，后台构建并校验后切换 |
| GET `/api/v1/jobs/{id}` | 创建任务的对账员 | 查询 PENDING / RUNNING / COMPLETED / FAILED、部署、目标区块、结果 ID 与错误 |
| POST `/api/v1/jobs/{id}/retry` | 创建任务的对账员 | 仅将 FAILED 改为 PENDING；重复调用不另建任务 |
| GET `/api/v1/reconciliations/{id}` | 对账员 | 查看当前部署的报告、逐项证据、bankEvidence 与差异解决报告 ID |

幂等域为 deployment + actor + kind + key。相同请求返回原任务；修改同一 key 的参数返回 409。重建任务结果 ID 是 projectionVersion，对账任务结果 ID 是 reconciliationId。后台进程在结果提交前退出时，重建沿用原版本，对账复用原报告 ID。

后台重建失败不会撤掉旧 ACTIVE。修复原因后重试原 job；另一个 job 遇到仍在 BUILDING 的版本会报错，而不是偷偷替换它。重建期间实时索引若向前推进，构建版本需追到该水位后才能切换，因此最终高度可高于提交时目标。

默认工作流租约 `tokenrail.lease-seconds=30`；管理任务租约 `tokenrail.admin-lease-seconds=30`，执行期间续租。RPC 不可用、身份无法确认时工作流停止本轮处理；过期任务会重新领取。确定的部署 hash 冲突需要配置新的有效部署，不能直接将旧部署改成新链。

报告的 POSITION_TOTAL_MISMATCH 表示资产持仓总量不匹配。其报告主状态仍为 POSITION_MISMATCH。修复后如需关闭旧差异，应在新投影中显式对原高度 H 发起复核，不能只对 latest 发起检查。
