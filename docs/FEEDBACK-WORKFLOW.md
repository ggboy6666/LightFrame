# 设备反馈与每月报告

这个流程在 GitHub 仓库外部运行：用户提交公开 Issue，维护者复测后标记状态，每月生成设备与指标报告。它不增加轻帧的功能或权限，不上传手机中的记录，不启动性能采集，不自动执行反馈中的代码或命令。

## 仓库发布后启用

1. 将 `.github/ISSUE_TEMPLATE/device-feedback.yml`、`.github/workflows/feedback-monthly.yml` 和 `scripts` 上传到公开仓库的默认分支，开启仓库的 Issues 和 Actions。
2. 在 Issues 的 Labels 页面建立下列标签。表单自动附加的标签需要事先存在；统计脚本也能识别完整的固定表单标题，避免初次缺标签时丢失反馈。[GitHub Issue Forms 文档](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms)
3. 在 Actions 页面选择“每月设备反馈报告”，点 Run workflow，选默认分支。月份留空会汇总北京时间上个月；也可填写 `2026-09` 这样的月份补跑。
4. 确认任务成功，且提交只包含 `docs/feedback` 中的 Markdown 和 CSV。流程使用当次任务的 `GITHUB_TOKEN`，权限为 `contents: write`、`issues: read`；不需要在手机或代码中保存个人令牌。[GitHub 自动令牌文档](https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication)

| 标签 | 维护者使用条件 | 报告含义 |
| --- | --- | --- |
| `device-feedback` | 设备反馈表单，或补齐必要字段的旧反馈 | 进入设备报告 |
| `verified-on-device` | 已在报告对应设备与配置上复现，Issue 中注明设备、版本、授权、指标与证据 | 已真机复现 |
| `adapted-on-device` | 已有针对该组合的适配；尚未复测时只加此标签 | 适配待复测 |
| `verified-on-device` 与 `adapted-on-device` 同时存在 | 维护者完成修复后真机复测，并补充实际测试版本与证据 | 已真机复测适配 |
| `duplicate` | 与已有反馈重复，Issue 中留下原反馈链接 | 保留逐 Issue 清单，排除报告组计数 |

上述标签需要维护者根据证据更新。如果修复版本与原表单版本不同，应在表单中更新版本字段或建立该版本的复测反馈，以免把一个版本的结论套用于另一版本。关闭 Issue、用户评论“已修好”或有一份修复代码，均不会自动变成已真机复测适配。

默认分支保护规则或组织策略若禁止 Actions 推送，报告任务会失败，远程旧报告仍保留。应根据仓库既有规则允许该报告提交，或由维护者手动提交本地生成的报告；流程不会强推或绕过分支规则。

## 月度时间与产物

计划为每月 1 日北京时间 20:00，对应 UTC cron `0 12 1 * *`。默认统计区间是上个月北京时间 1 日 00:00 到本月 1 日 00:00，包含起点、不含终点。例如 2026 年 10 月 1 日生成 2026 年 9 月报告。累计统计包含本次读取截止时间以前仍可访问的全部反馈，不受上月窗口限制。

GitHub 的定时任务只在默认分支运行；调度拥堵时可能延迟或丢弃，公开仓库连续 60 天无活动时可能自动关闭计划任务。出现这些情况可在 Actions 页面重新启用并手动补跑。[GitHub schedule 文档](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

每个月生成三个文件：

- `docs/feedback/YYYY-MM.md`：累计数量、上月新增数量、设备与指标分类，以及复测状态。
- `docs/feedback/YYYY-MM.csv`：逐 Issue 清单、报告人、字段、评论数量、归组标识和原始 Issue 链接。
- `docs/feedback/YYYY-MM-groups.csv`：按机型、Android、轻帧版本、授权方式和指标分类的报告组计数。

Markdown 中的 Issue 链接便于回看证据；CSV 为 UTF-8 BOM，便于用 Excel 打开。报告不复制评论全文或下载附件。

## 统计口径

固定字段为“机型”“Android 版本”“轻帧版本”“授权方式”“受影响指标”。带 `device-feedback` 标签，或包含全部固定标题的普通 Issue，进入设备统计。没有必要字段的反馈列为“未分类”；不会从聊天文字或附件中猜测机型与权限。授权方式和指标使用表单中的固定选项。

同一报告人、同一机型、Android、轻帧版本、授权方式和指标组合，只形成一个报告组。指标顺序不影响归组，报告人与自由文本字段忽略字母大小写和重复空白。每个原始 Issue 仍出现在逐 Issue CSV 中。`duplicate` 标签的 Issue 不创建报告组。报告组不是独立手机数量，也不是已经证实的独立故障数量。

上月“新报告组”根据该组最早的非 duplicate Issue 创建时间计算。已有组合在上月重复发帖，会增加新增 Issue 数，不增加新报告组。单条多指标反馈会分布在多个分类行，不能把分类行的数量相加当作设备或故障总数。

评论单列原始条数和去重条数：同一 Issue、同一作者、正文一致的评论只计一次，统一换行与行尾空格；不同作者保留各自一条。跨月重发以前相同评论，不计为新增去重评论。任何评论都不会创建报告组。编辑旧评论不计为新评论，本流程按 `created_at` 统计月份；报告内容和标签反映本次读取时的状态，不是过去月份的状态快照。

GitHub 的 Issues 端点也返回 PR，通过 `pull_request` 字段区分。PR 不进入设备统计；PR 会话评论单列，PR 行内代码审查评论不在本流程范围内，因此不额外申请 `pull-requests` 权限。[Issues API](https://docs.github.com/en/rest/issues/issues#list-repository-issues)、[仓库会话评论 API](https://docs.github.com/en/rest/issues/comments#list-issue-comments-for-a-repository)

## 完整性与失败处理

脚本读取开放和关闭的全部 Issues、全部仓库会话评论，每页 100 条，不用 `since` 截断累计历史。优先跟随 `Link` 中的下一页；短页仍有下一页时继续读取；恰好 100 条且未收到下一页时再试下一页。重叠页按 API 记录 ID 去重。分页地址必须属于相同 GitHub API 主机、仓库和端点，循环分页会失败。

读取后将每个 Issue / PR 实际收到的评论条数与 Issue 返回的 `comments` 数量比较。评论不足、存在未取得所属 Issue 的旧评论、无效 JSON、超大响应、网络失败或 API 错误都会使任务失败。每个请求超时为 20 秒，单页上限 16 MiB；可重试的服务或限流错误最多尝试 3 次。遵守 `Retry-After` 和限流重置时间；要求等待超过 60 秒时退出，等待维护者稍后重跑，不提前重试。[GitHub API 重试规则](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)

只有全部 API 读取、数量检查和报告生成通过后才写文件，因此 API 失败时原有报告逐字节保留。新报告先写临时文件，每个文件使用原子替换；远程发布由一次 Git 提交完成。此处不声称多个本地文件在断电时能一起原子替换。工作流提交步骤只允许报告目录中的 `.md` / `.csv`，不会提交或改写应用。

GitHub 分页读取不是事务快照。读取过程中评论被删除或 Issue 被转移，可能使完整性检查失败；重跑即可。已删除的 Issue / 评论无法由公开 API 恢复。“累计”表示本次仍可读取的数据，过去月份已提交的报告由 Git 历史保留。

反馈正文始终作为数据处理：不启动 shell，不执行其中指令，不输出令牌或 API 错误正文。Markdown 中转义 HTML、表格分隔符、链接标记和提及；CSV 对以 `= + - @` 开头的单元格加文本前缀，去除控制字符并使用规范 CSV 引号，降低公式注入风险。报告链接由已验证的仓库和 Issue 编号构造，不采用反馈里的链接。

## 本地验证和补跑

在仓库根目录，使用 Python 3.10 或更新版本运行离线测试；脚本仅使用标准库，不需要安装第三方依赖。测试使用模拟 API，不发送评论、不访问 GitHub、不需要令牌。

```powershell
python -m unittest discover -s scripts/tests -p 'test_collect_feedback.py' -v
```

已覆盖分页、重叠页、缺页保留旧文件、重试限制、同作者反馈去重、评论归组规则、PR 隔离、标签复测状态、北京时间月界、Markdown 转义与 CSV 公式注入。

维护者需要本地补跑时，可在仓库根目录填写实际已发布的仓库名称；下面的 `owner/repository` 是占位符。此命令只读 API 并生成本地报告，不会提交 Git 或发表评论。公开仓库可不带令牌，但会受到较低的未认证 API 配额限制。

```powershell
python scripts/collect_feedback.py --repo owner/repository --month 2026-09
```

工作流已经使用固定提交版本的 [actions/checkout v7](https://github.com/actions/checkout) 与 [actions/setup-python v7](https://github.com/actions/setup-python)，版本于 2026-10-01 从官方仓库核对。仓库实际创建、默认分支推送、Actions 启用和首次远程运行，需要在发布阶段完成；本地测试通过不等于远程流程已经运行成功。
