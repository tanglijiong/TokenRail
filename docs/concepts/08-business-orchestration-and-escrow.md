# M6：条件支付与三层状态

PaymentEscrow 只证明某个获授权地址在截止前提交了 `release`，不能证明货物送达、票据真实或法律条件已经满足。TokenRail 把这个地址明确称为测试确认员，并把现实世界证据留在链外。链上负责固定付款条款、限制调用者、守住时间边界和保证转账与终态原子提交。

FUNDED 资金属于合约负债。`outstanding` 只随 fund、release、refund 改变，所以直接向合约转入 Token 不会伪造付款负债。release 与 refund 都先写终态再调用 Token，并受重入保护；任一步 revert 会连同状态一起回滚。收款人被冻结时 release 失败，资金仍为 FUNDED；到期后付款人可退款。若付款人也被冻结或 Token 全局暂停，退款会继续失败并形成可见 HOLD，管理员没有绕过合规提取的入口。

业务 API 有三层容易混淆的状态。HTTP 202 只说明数据库已保存请求；SUCCEEDED 说明 receipt 成功并已持久化；PROJECTED 说明索引器也完整提交到该区块。页面同时展示 operationId、txHash、mined block 和 indexed block，让使用者可以判断卡在队列、链上还是投影，而不是从按钮点击推断成功。

本次失败实验包括未授权 release 返回 403、提前 refund 产生 status=0 并记录 REVERTED、退款后再次 release 仍 REVERTED。成功路径在同一套 HTTP API 中完成债券发行、存款转换、双方授权、DvP 与 Escrow 放款，随后索引器推进到 receipt 区块并显示 PROJECTED。证据见 [M6 本地验收](../evidence/m6-fullstack-20260914/README.md)。
