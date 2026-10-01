#!/usr/bin/env python3
"""Read repository feedback and publish only derived Markdown/CSV reports.

Standard library only. Issue text is data, never commands. API collection must
finish and pass completeness checks before any existing report is replaced.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

API = "https://api.github.com"
API_VERSION = "2026-03-10"
BEIJING = timezone(timedelta(hours=8))
MAX_RESPONSE_BYTES = 16 * 1024 * 1024
FIELDS = ("机型", "Android 版本", "轻帧版本", "授权方式", "受影响指标")
METRICS = (
    "FPS", "帧时间", "CPU 使用率", "GPU 使用率", "GPU 频率", "CPU 温度",
    "GPU 温度", "SoC 温度", "电池 / 功率", "内存", "网络", "历史记录 / 导出", "其他",
)
AUTHORIZATIONS = (
    "基础模式", "Shizuku Shell（UID 2000）", "Shizuku / Sui Root（UID 0）",
    "Root（UID 0）", "不确定",
)
STATUS_ORDER = ("未验证反馈", "适配待复测", "已真机复现", "已真机复测适配")


class CollectionError(Exception):
    """An incomplete/invalid read; no derived report should be published."""


def validate_repo(repo: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", repo or ""):
        raise CollectionError("仓库名称必须是 owner/repository")
    if repo.split("/")[1] in (".", ".."):
        raise CollectionError("无效的仓库名称")
    return repo


def parse_time(value: str) -> datetime:
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError()
        return result.astimezone(timezone.utc)
    except (AttributeError, ValueError, TypeError) as exc:
        raise CollectionError("反馈的时间字段无效") from exc


def month_window(month: str | None, now: datetime) -> tuple[str, datetime, datetime]:
    local_now = now.astimezone(BEIJING)
    if month is None:
        previous = local_now.replace(day=1) - timedelta(days=1)
        month = previous.strftime("%Y-%m")
    if not re.fullmatch(r"[0-9]{4}-(0[1-9]|1[0-2])", month):
        raise CollectionError("月份必须是 YYYY-MM")
    year, number = map(int, month.split("-"))
    try:
        begin = datetime(year, number, 1, tzinfo=BEIJING)
        end = datetime(year + (number == 12), 1 if number == 12 else number + 1, 1, tzinfo=BEIJING)
    except ValueError as exc:
        raise CollectionError("无效的报告月份") from exc
    if begin > local_now:
        raise CollectionError("不能生成未来月份的报告")
    return month, begin, end


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A renamed repository must be configured explicitly. Never forward a token.
        return None


def http_get(url: str, headers: dict[str, str], timeout: int):
    request = Request(url, headers=headers, method="GET")
    opener = build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
            return response.status, dict(response.headers.items()), body
    except HTTPError as exc:
        # Do not read/print server error bodies, which may contain private data.
        return exc.code, dict(exc.headers.items()), b""
    except (URLError, TimeoutError, OSError) as exc:
        raise CollectionError("GitHub API 网络读取失败；保留旧报告") from exc


def header_value(headers, key):
    return next((value for name, value in headers.items() if name.lower() == key.lower()), "")


class GitHubClient:
    def __init__(self, token="", transport=http_get, sleep=time.sleep, clock=time.time):
        self.transport, self.sleep, self.clock = transport, sleep, clock
        self.headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "LightFrame-monthly-feedback",
            "X-GitHub-Api-Version": API_VERSION,
        }
        if token:
            self.headers["Authorization"] = "Bearer " + token

    def _get(self, url):
        for attempt in range(3):
            status, headers, body = self.transport(url, self.headers, 20)
            if len(body) > MAX_RESPONSE_BYTES:
                raise CollectionError("GitHub API 单页输出超过容量限制")
            if status == 200:
                try:
                    values = json.loads(body)
                except (ValueError, UnicodeDecodeError) as exc:
                    raise CollectionError("GitHub API 返回无效 JSON") from exc
                if not isinstance(values, list):
                    raise CollectionError("GitHub API 返回了非列表结果")
                return values, headers
            limited = status in (403, 429) and (
                status == 429 or header_value(headers, "Retry-After")
                or header_value(headers, "X-RateLimit-Remaining") == "0"
            )
            if not limited and status not in (500, 502, 503, 504):
                raise CollectionError(f"GitHub API HTTP {status}；保留旧报告")
            if attempt == 2:
                raise CollectionError(f"GitHub API HTTP {status} 重试后仍失败；保留旧报告")
            delay = 2 ** attempt
            try:
                retry_after = header_value(headers, "Retry-After")
                if retry_after:
                    delay = max(delay, float(retry_after))
                if header_value(headers, "X-RateLimit-Remaining") == "0":
                    delay = max(delay, float(header_value(headers, "X-RateLimit-Reset")) - self.clock() + 1)
            except (TypeError, ValueError) as exc:
                raise CollectionError("GitHub API 限流等待字段无效") from exc
            if delay > 60:
                raise CollectionError("GitHub API 限流等待超过单次限制；请稍后重跑，旧报告保留")
            self.sleep(max(0, delay))
        raise CollectionError("GitHub API 读取失败")

    def all(self, path: str, params=None) -> list[dict]:
        params = {**(params or {}), "per_page": "100", "page": "1"}
        url = API + path + "?" + urlencode(params)
        visited, items = set(), {}
        while url:
            parsed = urlsplit(url)
            if parsed.scheme != "https" or parsed.netloc != "api.github.com" or parsed.path != path or parsed.fragment:
                raise CollectionError("GitHub API 分页地址超出当前仓库端点")
            if url in visited or len(visited) >= 10000:
                raise CollectionError("GitHub API 分页循环或超过页数限制")
            visited.add(url)
            values, headers = self._get(url)
            if len(values) > 100:
                raise CollectionError("GitHub API 页大小异常")
            for value in values:
                if not isinstance(value, dict) or type(value.get("id")) is not int or value["id"] <= 0:
                    raise CollectionError("GitHub API 记录缺少有效 ID")
                items[value["id"]] = value
            link = header_value(headers, "Link")
            next_links = re.findall(r'<([^>]+)>\s*;\s*rel="next"', link)
            if len(next_links) > 1:
                raise CollectionError("GitHub API 存在多个下一页")
            if next_links:
                url = next_links[0]  # A short page may still have a next page.
            elif len(values) == 100:
                query = dict(parse_qsl(parsed.query))
                try:
                    query["page"] = str(int(query.get("page", "1")) + 1)
                except ValueError as exc:
                    raise CollectionError("GitHub API 页码无效") from exc
                url = urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), ""))
            else:
                url = None
        return list(items.values())


def plain(value, limit=None):
    value = str(value or "")
    value = "".join(char if unicodedata.category(char) not in ("Cc", "Cf") else " " for char in value)
    value = " ".join(value.split())
    return value if limit is None else value[:limit]


def markdown(value):
    value = html.escape(plain(value, 200), quote=True)
    # User text must not become a table delimiter, link, mention, or formatting.
    for char in "|`[]*_\\@":
        value = value.replace(char, f"&#{ord(char)};")
    return value


def csv_cell(value):
    value = plain(value)
    return "'" + value if value.lstrip().startswith(("=", "+", "-", "@")) else value


def form_fields(body):
    result = {}
    for match in re.finditer(r"(?m)^### ([^\r\n]+)\r?\n", body or ""):
        begin = match.end()
        end = re.search(r"(?m)^### ", (body or "")[begin:])
        value = (body or "")[begin:begin + end.start() if end else None].strip()
        result[match.group(1).strip()] = value
    return result


def labels_of(issue):
    labels = issue.get("labels", [])
    if not isinstance(labels, list):
        raise CollectionError("Issue 标签字段无效")
    return {value.get("name", "") if isinstance(value, dict) else str(value) for value in labels}


def status_of(labels):
    verified, adapted = "verified-on-device" in labels, "adapted-on-device" in labels
    return STATUS_ORDER[3 if verified and adapted else 2 if verified else 1 if adapted else 0]


def comment_key(comment):
    user = comment.get("user") or {}
    author = user.get("login") or "deleted-user"
    # Preserve meaningful punctuation/newlines; normalize only line endings/spacing.
    normalized = "\n".join(line.rstrip() for line in (comment.get("body") or "").replace("\r\n", "\n").split("\n")).strip()
    return author.casefold(), hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def validate_text_record(record):
    if record.get("body") is not None and not isinstance(record["body"], str):
        raise CollectionError("反馈正文不是文本")
    user = record.get("user")
    if user is not None and (not isinstance(user, dict) or (
        user.get("login") is not None and not isinstance(user["login"], str)
    )):
        raise CollectionError("反馈作者字段无效")


def collect(client, repo, now):
    repo = validate_repo(repo)
    root = "/repos/" + repo
    issues = client.all(root + "/issues", {"state": "all", "sort": "created", "direction": "asc"})
    comments = client.all(root + "/issues/comments", {"sort": "created", "direction": "asc"})
    by_number = {}
    for issue in issues:
        validate_text_record(issue)
        number, expected = issue.get("number"), issue.get("comments")
        if type(number) is not int or number <= 0 or type(expected) is not int or expected < 0:
            raise CollectionError("Issue 编号或评论总数字段无效")
        if number in by_number:
            raise CollectionError("Issue 编号重复")
        parse_time(issue.get("created_at"))
        by_number[number] = issue
    attached = defaultdict(list)
    expected_prefix = API + root + "/issues/"
    for comment in comments:
        validate_text_record(comment)
        url = comment.get("issue_url", "")
        suffix = url[len(expected_prefix):] if isinstance(url, str) and url.startswith(expected_prefix) else ""
        if not re.fullmatch(r"[1-9][0-9]*", suffix):
            raise CollectionError("评论所属 Issue 地址无效")
        number = int(suffix)
        created = parse_time(comment.get("created_at"))
        if number not in by_number:
            if created <= now:
                raise CollectionError("存在未取得所属 Issue 的评论；反馈分页不完整")
            continue
        attached[number].append(comment)
    for number, issue in by_number.items():
        if len(attached[number]) < issue["comments"]:
            raise CollectionError(f"Issue #{number} 评论不完整；保留旧报告")
    return issues, attached


def csv_bytes(columns, rows):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(columns)
    writer.writerows([[csv_cell(value) for value in row] for row in rows])
    return stream.getvalue().encode("utf-8-sig")


def build_reports(issues, attached, repo, now, month=None):
    repo = validate_repo(repo)
    month, begin, end = month_window(month, now)
    rows, groups = [], {}
    totals = Counter()
    for issue in sorted(issues, key=lambda item: item["number"]):
        created = parse_time(issue["created_at"])
        if created > now:
            continue
        number = issue["number"]
        issue_comments = [item for item in attached.get(number, []) if parse_time(item["created_at"]) <= now]
        is_pr = "pull_request" in issue
        kind = "pr" if is_pr else "issue"
        totals[kind + "s"] += 1
        totals[kind + "_comments"] += len(issue_comments)
        totals["new_" + kind + "s"] += begin <= created < end
        totals["new_" + kind + "_comments"] += sum(begin <= parse_time(item["created_at"]) < end for item in issue_comments)
        unique_comments = {comment_key(item) for item in issue_comments}
        totals[kind + "_unique_comments"] += len(unique_comments)
        new_unique_comments = {
            comment_key(item) for item in issue_comments
            if begin <= parse_time(item["created_at"]) < end
        } - {comment_key(item) for item in issue_comments if parse_time(item["created_at"]) < begin}
        totals["new_" + kind + "_unique_comments"] += len(new_unique_comments)
        if is_pr:
            continue
        fields, labels = form_fields(issue.get("body")), labels_of(issue)
        if "device-feedback" not in labels and not all(label in fields for label in FIELDS):
            continue
        def field(label):
            value = plain(fields.get(label, ""))
            return "未分类" if not value or value == "_No response_" else value
        model, android, version = (field(label) for label in FIELDS[:3])
        auth = field("授权方式")
        if auth not in AUTHORIZATIONS:
            auth = "未分类"
        metric_values = [plain(value) for value in re.split(r"[,，\n]", fields.get("受影响指标", ""))]
        metrics = tuple(sorted(set(value for value in metric_values if value in METRICS)))
        if not metrics:
            metrics = ("未分类",)
        author = plain((issue.get("user") or {}).get("login")) or f"deleted-user-issue-{number}"
        status = status_of(labels)
        duplicate = "duplicate" in labels
        url = f"https://github.com/{repo}/issues/{number}"
        key = tuple(value.casefold() for value in (author, model, android, version, auth)) + (metrics,)
        group_id = hashlib.sha256(json.dumps(key, ensure_ascii=False).encode("utf-8")).hexdigest()[:16]
        row = {
            "number": number, "url": url, "author": author, "model": model, "android": android,
            "version": version, "auth": auth, "metrics": metrics, "status": status,
            "duplicate": duplicate, "created": created, "comments": len(issue_comments),
            "unique_comments": len(unique_comments), "new_comments": sum(begin <= parse_time(item["created_at"]) < end for item in issue_comments),
            "new_unique_comments": len(new_unique_comments), "group_id": group_id,
        }
        rows.append(row)
        if not duplicate:
            if key not in groups:
                groups[key] = {"row": row, "numbers": [], "created": created, "status": status}
            group = groups[key]
            group["numbers"].append(number)
            group["created"] = min(created, group["created"])
            if STATUS_ORDER.index(status) > STATUS_ORDER.index(group["status"]):
                group["status"] = status
    categories = defaultdict(lambda: {"groups": set(), "new": set(), "statuses": Counter(), "issues": set()})
    for group_id, (key, group) in enumerate(groups.items()):
        row = group["row"]
        for metric in row["metrics"]:
            category = (row["model"], row["android"], row["version"], row["auth"], metric)
            bucket = categories[category]
            bucket["groups"].add(group_id)
            if begin <= group["created"] < end:
                bucket["new"].add(group_id)
            bucket["statuses"][group["status"]] += 1
            bucket["issues"].update(group["numbers"])
    category_rows = []
    for category, bucket in sorted(categories.items()):
        category_rows.append((*category, len(bucket["groups"]), len(bucket["new"]),
                              *(bucket["statuses"][status] for status in STATUS_ORDER),
                              " ".join("#" + str(number) for number in sorted(bucket["issues"]))))
    new_groups = sum(begin <= group["created"] < end for group in groups.values())
    snapshot = now.astimezone(timezone.utc).isoformat(timespec="seconds")
    lines = [
        f"# 设备反馈报告：{month}", "",
        f"仓库：[{repo}](https://github.com/{repo})。读取截止时间：{snapshot}。",
        f"上月窗口：{begin.isoformat()} 至 {end.isoformat()}（左闭右开，北京时间）。", "",
        "统计只读取公开的 GitHub Issues 和仓库会话评论，不读取手机、不启动采集，也不执行评论中的内容。",
        "Issue / 标签为本次读取时的状态；GitHub 分页接口没有事务快照。所有页读取与评论数量检查通过后才生成报告。", "",
        "## 数量", "",
        "| 范围 | 累计 | 报告月份新增 |", "| --- | ---: | ---: |",
        f"| 普通 Issues（含非设备反馈） | {totals['issues']} | {totals['new_issues']} |",
        f"| 设备反馈 Issues | {len(rows)} | {sum(begin <= row['created'] < end for row in rows)} |",
        f"| 设备报告组（排除 duplicate 标签） | {len(groups)} | {new_groups} |",
        f"| 普通 Issue 评论（原始条数） | {totals['issue_comments']} | {totals['new_issue_comments']} |",
        f"| 普通 Issue 评论（去重条数） | {totals['issue_unique_comments']} | {totals['new_issue_unique_comments']} |",
        f"| PR | {totals['prs']} | {totals['new_prs']} |",
        f"| PR 会话评论（原始条数） | {totals['pr_comments']} | {totals['new_pr_comments']} |",
        f"| PR 会话评论（去重条数） | {totals['pr_unique_comments']} | {totals['new_pr_unique_comments']} |", "",
        "报告组按报告人、机型、Android 版本、应用版本、授权方式和指标组合合并；它不是独立设备数或已证实故障数。",
        "同一 Issue 内、同一作者的相同评论只计一次；跨月重发旧评论不计新增去重评论。评论不会增加设备报告组。",
        "多指标报告可出现在多行，因此分类行数量不能相加作为独立故障数。缺失 / 非标准字段列为未分类。",
        "PR 会话评论只单列计数；PR 行内代码审查评论不在本流程范围内。", "",
        "## 设备与指标分类", "",
        "| 机型 | Android | 轻帧版本 | 授权 | 指标 | 累计报告组 | 新报告组 | 未验证 | 适配待复测 | 已真机复现 | 已真机复测适配 | Issues |",
        "| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for values in category_rows:
        links = " ".join(f"[#{number}](https://github.com/{repo}/issues/{number})" for number in map(int, values[-1].replace("#", "").split()))
        lines.append("| " + " | ".join(markdown(value) for value in values[:-1]) + " | " + links + " |")
    if not category_rows:
        lines.append("| 无设备报告组 | — | — | — | — | 0 | 0 | 0 | 0 | 0 | 0 | — |")
    lines += ["", "复测状态来自维护者标签：`verified-on-device` 表示真机复现；同时含 `adapted-on-device` 才列为已真机复测适配。",
              "仅有适配标签列为待复测；关闭 Issue 或评论声称修复不会自动变成已适配。适配结论仅覆盖表内设备、系统、版本和授权组合。", "",
              f"下载：[逐 Issue CSV]({month}.csv)、[分类 CSV]({month}-groups.csv)。CSV 保留报告人和原始 Issue 链接，便于检查合并结果。", ""]
    issue_columns = ("issue", "url", "reporter", "model", "android", "app_version", "authorization", "metrics", "verification_status", "duplicate_label", "report_group", "created_at", "comments", "unique_comments", "month_comments", "month_unique_comments")
    issue_values = [
        (row["number"], row["url"], row["author"], row["model"], row["android"], row["version"], row["auth"], "; ".join(row["metrics"]), row["status"], row["duplicate"], row["group_id"], row["created"].isoformat(), row["comments"], row["unique_comments"], row["new_comments"], row["new_unique_comments"])
        for row in rows
    ]
    group_columns = ("model", "android", "app_version", "authorization", "metric", "report_groups", "month_new_groups", "unverified", "adapted_awaiting_retest", "verified_on_device", "adapted_verified_on_device", "issues")
    return {
        month + ".md": "\n".join(lines).encode("utf-8"),
        month + ".csv": csv_bytes(issue_columns, issue_values),
        month + "-groups.csv": csv_bytes(group_columns, category_rows),
    }


def write_reports(output_dir, reports):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    staged = []
    try:
        for name, data in reports.items():
            with tempfile.NamedTemporaryFile(dir=output_dir, prefix=".feedback-", suffix=".tmp", delete=False) as stream:
                staged.append((Path(stream.name), output_dir / name))
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        # Atomic per-file replacement. Git commits make the published set atomic.
        for temporary, target in staged:
            os.replace(temporary, target)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)


def run(client, repo, output_dir, month=None, now=None):
    now = now or datetime.now(timezone.utc)
    # Validate inputs before network access and do not touch reports on read failure.
    validate_repo(repo)
    month_window(month, now)
    issues, comments = collect(client, repo, now)
    reports = build_reports(issues, comments, repo, now, month)
    write_reports(output_dir, reports)
    return sorted(reports)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--month", help="YYYY-MM；默认北京时间上个月")
    parser.add_argument("--output-dir", default="docs/feedback")
    args = parser.parse_args(argv)
    try:
        names = run(GitHubClient(os.environ.get("GITHUB_TOKEN", "")), args.repo, args.output_dir, args.month)
    except (CollectionError, OSError) as exc:
        print(f"反馈报告未更新：{exc}", file=sys.stderr)
        return 1
    print("反馈报告已生成：" + ", ".join(names))
    return 0


if __name__ == "__main__":
    sys.exit(main())
