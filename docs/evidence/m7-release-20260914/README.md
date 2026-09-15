# M7 干净环境发布验收

- 发布候选 commit：`cd78115f641a3d210756e490249bd0603d501c26`
- 验收时间：2026-09-14T17:31:05Z 至 2026-09-14T17:36:29Z
- 环境：macOS 15.5 arm64，Java 21.0.12.1，Maven 3.9.9，Node.js 25.8.1，npm 11.11.0，Docker 26.1.4
- 源码来源：已提交 commit 的 `git archive`，解包到全新临时目录后重装依赖
- 结论：`M7_CLEAN_RELEASE=PASS`

## 发布门禁

| 层级 | 结果 | 关键证据 |
|---|---|---|
| Solidity | PASS | 51/51；包含 256 组 fuzz 和 3 条状态不变量 |
| Anvil | PASS | M0 成功/回滚 receipt；M2 第二腿 `status=0x0`、0 logs、余额/allowance/READY 不变 |
| Besu QBFT | PASS | 4 validators / 3 peers；停 1 节点继续出块，停 2 节点停块，恢复后继续 |
| M4–M5 | PASS | 8/8 真实 Besu/PostgreSQL 集成测试 |
| M5.1 | PASS | 8/8 进程崩溃、链重置、租约、账务和权限测试 |
| M6 | PASS | 2/2 业务 API、PaymentEscrow 与投影全栈测试 |
| 发布文件 | PASS | Apache-2.0 `LICENSE` 与 `RELEASE_NOTES.md` 存在；归档无 `.env`、key 或 keystore |

完整输出见 [clean-release.log](clean-release.log)，本次链的合约地址、部署交易、ABI 和 bytecode 哈希见 [clean-deployment-manifest.json](clean-deployment-manifest.json)，M6 场景细节见 [clean-m6-verify.log](clean-m6-verify.log)。

## 三场景演示

`scripts/demo-m7-scenarios.sh` 每次部署新合约并执行：

1. 条件支付：一笔由确认员 release，另一笔提前 refund 失败、到期后 refund 成功。
2. DvP：一笔券款原子交割成功；另一笔资金不足失败，失败前后四个余额一致。
3. 合规：冻结后 DvP 执行失败，显式发送的普通 transfer receipt 为 `0x0`；解冻后原 READY 指令成功 SETTLED。

结构化交易和投影证据见 [scenario-results.json](scenario-results.json)。三场景录屏见 [tokenrail-v0.1-demo.mp4](tokenrail-v0.1-demo.mp4)：H.264、1280×720、27.97 秒、421461 字节，SHA-256 为 `71f3bd833d49d2f81a576e29f47654fdf10b4d49ff7fbab7262bd9308ff0935b`。录制时 Playwright 控制台为 0 errors / 0 warnings，媒体参数见 [video-metadata.json](video-metadata.json)。

## 复现

```bash
# 要求 git 工作树已提交且干净
scripts/test-m7-release.sh

# 仅执行三场景并保留 http://127.0.0.1:18081/
scripts/demo-m7-scenarios.sh --keep-running

# 重新执行场景、浏览器取证和 MP4 生成
scripts/record-m7-demo.sh
```

GitHub Actions 的 `Release acceptance` workflow 在 `v*` tag 和手动触发时运行同一干净验收脚本。本文档记录本地实际结果；远程 Actions 状态以仓库页面为准。
