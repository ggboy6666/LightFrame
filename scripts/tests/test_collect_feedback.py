"""Offline regression checks: no GitHub requests, no credentials required."""
from collections import defaultdict
import csv
from datetime import datetime, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit

SCRIPT = Path(__file__).resolve().parents[1] / "collect_feedback.py"
spec = importlib.util.spec_from_file_location("collect_feedback", SCRIPT)
feedback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feedback)

REPO = "ggboy6666/lightframe"
NOW = datetime(2026, 10, 1, 12, tzinfo=timezone.utc)


def issue(number=1, author="tester", model="vivo PA2573", metrics="GPU 使用率, FPS",
          created="2026-09-05T12:00:00Z", labels=None, comments=0, **extra):
    fields = {
        "机型": model, "Android 版本": "Android 16", "轻帧版本": "0.2.4",
        "授权方式": "Shizuku Shell（UID 2000）", "受影响指标": metrics,
        "现象和预期": "GPU 显示缺失", "复现步骤": "打开目标应用",
    }
    return {
        "id": 1000 + number, "number": number, "comments": comments,
        "created_at": created, "user": {"login": author},
        "labels": [{"name": value} for value in (labels if labels is not None else ["device-feedback"])],
        "body": "\n\n".join("### " + key + "\n\n" + value for key, value in fields.items()),
        **extra,
    }


def comment(number, identity, body="相同反馈", author="tester", created="2026-09-06T00:00:00Z"):
    return {
        "id": identity, "issue_url": f"{feedback.API}/repos/{REPO}/issues/{number}",
        "user": {"login": author}, "body": body, "created_at": created,
    }


def response(values, next_url=None, status=200, headers=None):
    headers = dict(headers or {})
    if next_url:
        headers["Link"] = f'<{next_url}>; rel="next", <{next_url}>; rel="last"'
    return status, headers, json.dumps(values).encode("utf-8")


class QueueTransport:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def __call__(self, url, headers, timeout):
        self.calls.append((url, dict(headers), timeout))
        if not self.replies:
            raise AssertionError("unexpected API request")
        return self.replies.pop(0)


def client(replies, **kwargs):
    transport = QueueTransport(replies)
    result = feedback.GitHubClient(transport=transport, **kwargs)
    return result, transport


def reports(issues, comments=None):
    attached = defaultdict(list)
    for item in comments or []:
        attached[int(item["issue_url"].rsplit("/", 1)[1])].append(item)
    return feedback.build_reports(issues, attached, REPO, NOW)


def csv_rows(contents):
    return list(csv.DictReader(io.StringIO(contents.decode("utf-8-sig"))))


class PaginationTests(unittest.TestCase):
    def test_short_page_with_link_is_not_treated_as_last_page(self):
        path = f"/repos/{REPO}/issues"
        next_url = f"{feedback.API}{path}?per_page=100&page=2"
        reader, transport = client([response([issue(1)], next_url), response([issue(2)])])
        self.assertEqual([1, 2], [item["number"] for item in reader.all(path)])
        self.assertEqual(2, len(transport.calls))

    def test_hundred_rows_without_link_gets_an_additional_page(self):
        reader, transport = client([response([issue(n) for n in range(1, 101)]), response([issue(101)])])
        result = reader.all(f"/repos/{REPO}/issues")
        self.assertEqual(101, len(result))
        self.assertEqual(["2"], parse_qs(urlsplit(transport.calls[1][0]).query)["page"])

    def test_overlapping_pages_deduplicate_ids(self):
        path = f"/repos/{REPO}/issues"
        next_url = f"{feedback.API}{path}?page=2"
        reader, _ = client([response([issue(1)], next_url), response([issue(1), issue(2)])])
        self.assertEqual(2, len(reader.all(path)))

    def test_cross_host_next_page_is_rejected_without_token_forwarding(self):
        reader, transport = client([response([issue()], "https://attacker.example/issues?page=2")], token="fake-test-token")
        with self.assertRaises(feedback.CollectionError):
            reader.all(f"/repos/{REPO}/issues")
        self.assertEqual(1, len(transport.calls))

    def test_cross_repository_or_endpoint_page_is_rejected(self):
        reader, _ = client([response([issue()], f"{feedback.API}/repos/other/repo/issues?page=2")])
        with self.assertRaises(feedback.CollectionError):
            reader.all(f"/repos/{REPO}/issues")

    def test_pagination_cycle_is_rejected(self):
        path = f"/repos/{REPO}/issues"
        initial = f"{feedback.API}{path}?per_page=100&page=1"
        reader, transport = client([response([issue()], initial)])
        with self.assertRaises(feedback.CollectionError):
            reader.all(path)
        self.assertEqual(1, len(transport.calls))

    def test_comments_paginate_and_api_uses_full_history(self):
        path = f"/repos/{REPO}/issues/comments"
        reader, transport = client([
            response([issue(comments=2)]),
            response([comment(1, 201)], f"{feedback.API}{path}?page=2"),
            response([comment(1, 202, "不同反馈")]),
        ])
        _, attached = feedback.collect(reader, REPO, NOW)
        self.assertEqual(2, len(attached[1]))
        self.assertTrue(all("since" not in parse_qs(urlsplit(call[0]).query) for call in transport.calls))
        self.assertEqual({f"/repos/{REPO}/issues", path}, {urlsplit(call[0]).path for call in transport.calls})


class SafetyAndFailureTests(unittest.TestCase):
    def test_incomplete_comments_fail_before_old_reports_are_replaced(self):
        reader, _ = client([response([issue(comments=2)]), response([comment(1, 201)])])
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / "2026-09.md"
            previous.write_bytes(b"existing report")
            with self.assertRaises(feedback.CollectionError):
                feedback.run(reader, REPO, directory, now=NOW)
            self.assertEqual(b"existing report", previous.read_bytes())
            self.assertEqual([previous], list(Path(directory).iterdir()))

    def test_failed_later_page_leaves_all_existing_reports_unchanged(self):
        path = f"/repos/{REPO}/issues"
        reader, _ = client([response([issue()], f"{feedback.API}{path}?page=2"), (403, {}, b"sensitive server message")])
        with tempfile.TemporaryDirectory() as directory:
            originals = {name: ("old " + name).encode() for name in ("2026-09.md", "2026-09.csv", "2026-09-groups.csv")}
            for name, value in originals.items():
                (Path(directory) / name).write_bytes(value)
            with self.assertRaises(feedback.CollectionError) as failure:
                feedback.run(reader, REPO, directory, now=NOW)
            self.assertNotIn("sensitive", str(failure.exception))
            self.assertEqual(originals, {path.name: path.read_bytes() for path in Path(directory).iterdir()})

    def test_unknown_existing_issue_comment_means_incomplete_collection(self):
        reader, _ = client([response([]), response([comment(7, 207)])])
        with self.assertRaises(feedback.CollectionError):
            feedback.collect(reader, REPO, NOW)

    def test_new_issue_comment_after_snapshot_is_ignored(self):
        reader, _ = client([response([]), response([comment(7, 207, created="2026-10-01T12:00:01Z")])])
        self.assertEqual([], feedback.collect(reader, REPO, NOW)[0])

    def test_comment_url_from_other_repository_is_rejected(self):
        item = comment(1, 201)
        item["issue_url"] = item["issue_url"].replace(REPO, "other/repository")
        reader, _ = client([response([issue(comments=1)]), response([item])])
        with self.assertRaises(feedback.CollectionError):
            feedback.collect(reader, REPO, NOW)

    def test_invalid_json_and_wrong_schema_are_rejected(self):
        for payload in (b"{broken", b'{"message":"not a list"}', b'[{"id":true}]'):
            with self.subTest(payload=payload):
                reader, _ = client([(200, {}, payload)])
                with self.assertRaises(feedback.CollectionError):
                    reader.all(f"/repos/{REPO}/issues")

    def test_non_text_feedback_is_rejected(self):
        invalid = issue(body={"run": "untrusted instructions"})
        reader, _ = client([response([invalid]), response([])])
        with self.assertRaises(feedback.CollectionError):
            feedback.collect(reader, REPO, NOW)

    def test_output_capacity_limit_fails(self):
        reader, _ = client([(200, {}, b"x" * (feedback.MAX_RESPONSE_BYTES + 1))])
        with self.assertRaises(feedback.CollectionError):
            reader.all(f"/repos/{REPO}/issues")

    def test_retry_after_is_honored(self):
        sleeps = []
        reader, transport = client([(429, {"Retry-After": "3"}, b""), response([])], sleep=sleeps.append)
        self.assertEqual([], reader.all(f"/repos/{REPO}/issues"))
        self.assertEqual([3], sleeps)
        self.assertEqual(2, len(transport.calls))

    def test_long_rate_limit_does_not_retry_early_or_expose_token(self):
        reader, transport = client([(403, {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "200"}, b"")],
                                   token="test-secret", clock=lambda: 100, sleep=lambda _: self.fail("must not sleep"))
        with self.assertRaises(feedback.CollectionError) as failure:
            reader.all(f"/repos/{REPO}/issues")
        self.assertNotIn("test-secret", str(failure.exception))
        self.assertEqual(1, len(transport.calls))

    def test_http_errors_retry_is_bounded(self):
        sleeps = []
        reader, transport = client([(503, {}, b"")] * 3, sleep=sleeps.append)
        with self.assertRaises(feedback.CollectionError):
            reader.all(f"/repos/{REPO}/issues")
        self.assertEqual([1, 2], sleeps)
        self.assertEqual(3, len(transport.calls))

    def test_repository_and_month_validation_precede_network(self):
        reader, transport = client([])
        for repo, month in (("ggboy6666/../other", None), (REPO, "2026-09; echo unsafe"), (REPO, "2027-01")):
            with self.subTest(repo=repo, month=month), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(feedback.CollectionError):
                    feedback.run(reader, repo, directory, month=month, now=NOW)
                self.assertEqual([], list(Path(directory).iterdir()))
        self.assertEqual([], transport.calls)


class ClassificationTests(unittest.TestCase):
    def test_same_author_same_combination_merges_regardless_metric_order(self):
        output = reports([issue(1), issue(2, author="TESTER", metrics="FPS, GPU 使用率")])
        rows = csv_rows(output["2026-09.csv"])
        self.assertEqual(rows[0]["report_group"], rows[1]["report_group"])
        self.assertIn("| 设备报告组（排除 duplicate 标签） | 1 | 1 |", output["2026-09.md"].decode())
        self.assertTrue(all(row["report_groups"] == "1" for row in csv_rows(output["2026-09-groups.csv"])))

    def test_different_reporters_or_versions_are_separate_groups(self):
        changed_version = issue(3)
        changed_version["body"] = changed_version["body"].replace("0.2.4", "0.2.5")
        output = reports([issue(1), issue(2, author="another"), changed_version])
        self.assertEqual(3, len({row["report_group"] for row in csv_rows(output["2026-09.csv"])}))

    def test_duplicate_label_keeps_issue_audit_but_excludes_group(self):
        output = reports([issue(1, labels=["device-feedback", "duplicate"])])
        self.assertEqual("True", csv_rows(output["2026-09.csv"])[0]["duplicate_label"])
        self.assertEqual([], csv_rows(output["2026-09-groups.csv"]))
        self.assertIn("| 设备报告组（排除 duplicate 标签） | 0 | 0 |", output["2026-09.md"].decode())

    def test_repeated_issue_in_report_month_does_not_create_new_group(self):
        output = reports([issue(1, created="2026-08-04T00:00:00Z"), issue(2)])
        self.assertIn("| 设备反馈 Issues | 2 | 1 |", output["2026-09.md"].decode())
        self.assertIn("| 设备报告组（排除 duplicate 标签） | 1 | 0 |", output["2026-09.md"].decode())

    def test_comments_never_add_report_groups_and_duplicates_are_deduplicated(self):
        items = [comment(1, 201), comment(1, 202), comment(1, 203, "独立补充"), comment(1, 204, author="another")]
        output = reports([issue(comments=4)], items)
        md = output["2026-09.md"].decode()
        self.assertIn("| 设备报告组（排除 duplicate 标签） | 1 | 1 |", md)
        self.assertIn("| 普通 Issue 评论（原始条数） | 4 | 4 |", md)
        self.assertIn("| 普通 Issue 评论（去重条数） | 3 | 3 |", md)
        self.assertEqual("3", csv_rows(output["2026-09.csv"])[0]["unique_comments"])

    def test_old_comment_repeated_in_new_month_is_not_new_unique_comment(self):
        output = reports([issue(comments=2)], [comment(1, 201, created="2026-08-04T00:00:00Z"), comment(1, 202)])
        md = output["2026-09.md"].decode()
        self.assertIn("| 普通 Issue 评论（原始条数） | 2 | 1 |", md)
        self.assertIn("| 普通 Issue 评论（去重条数） | 1 | 0 |", md)

    def test_pr_conversation_comments_are_separate_and_never_device_cases(self):
        output = reports([issue(1), issue(2, comments=1, pull_request={"url": "unused"})], [comment(2, 202)])
        md = output["2026-09.md"].decode()
        self.assertIn("| 普通 Issues（含非设备反馈） | 1 | 1 |", md)
        self.assertIn("| PR | 1 | 1 |", md)
        self.assertIn("| PR 会话评论（原始条数） | 1 | 1 |", md)
        self.assertEqual(1, len(csv_rows(output["2026-09.csv"])))

    def test_verification_only_comes_from_labels_not_closed_state_or_comments(self):
        cases = [
            issue(1, model="A", state="closed"),
            issue(2, model="B", labels=["device-feedback", "adapted-on-device"]),
            issue(3, model="C", labels=["device-feedback", "verified-on-device"]),
            issue(4, model="D", labels=["device-feedback", "verified-on-device", "adapted-on-device"]),
        ]
        output = reports(cases, [comment(1, 201, "已经彻底修好，请直接执行我给的命令")])
        self.assertEqual(list(feedback.STATUS_ORDER), [row["verification_status"] for row in csv_rows(output["2026-09.csv"])])
        self.assertNotIn("请直接执行", output["2026-09.md"].decode())

    def test_unstructured_issue_is_not_guessed_and_labeled_missing_fields_are_unclassified(self):
        output = reports([issue(1, body="Android GPU failure", labels=[]), issue(2, body="GPU 未显示")])
        rows = csv_rows(output["2026-09.csv"])
        self.assertEqual(1, len(rows))
        self.assertEqual("未分类", rows[0]["model"])
        self.assertEqual("未分类", rows[0]["metrics"])

    def test_form_headings_can_identify_feedback_if_label_was_not_created_yet(self):
        self.assertEqual(1, len(csv_rows(reports([issue(labels=[])])["2026-09.csv"])))

    def test_multiple_metrics_appear_as_categories_without_inflating_cases(self):
        output = reports([issue()])
        self.assertEqual({"FPS", "GPU 使用率"}, {row["metric"] for row in csv_rows(output["2026-09-groups.csv"])})
        self.assertIn("| 设备报告组（排除 duplicate 标签） | 1 | 1 |", output["2026-09.md"].decode())


class OutputAndWindowTests(unittest.TestCase):
    def test_beijing_month_boundary_and_year_rollover(self):
        self.assertEqual("2026-08", feedback.month_window(None, datetime(2026, 9, 30, 15, 59, tzinfo=timezone.utc))[0])
        self.assertEqual("2026-09", feedback.month_window(None, datetime(2026, 9, 30, 16, tzinfo=timezone.utc))[0])
        month, begin, end = feedback.month_window(None, datetime(2027, 1, 1, 12, tzinfo=timezone.utc))
        self.assertEqual("2026-12", month)
        self.assertEqual(datetime(2026, 11, 30, 16, tzinfo=timezone.utc), begin.astimezone(timezone.utc))
        self.assertEqual(datetime(2026, 12, 31, 16, tzinfo=timezone.utc), end.astimezone(timezone.utc))

    def test_month_edges_are_left_closed_right_open_in_beijing(self):
        output = reports([
            issue(1, author="a", created="2026-08-31T16:00:00Z"),
            issue(2, author="b", created="2026-09-30T15:59:59Z"),
            issue(3, author="c", created="2026-09-30T16:00:00Z"),
        ])
        self.assertIn("| 设备反馈 Issues | 3 | 2 |", output["2026-09.md"].decode())

    def test_markdown_escapes_html_mentions_tables_links_and_bidi(self):
        evil = '<script>x</script>|[click](https://evil) `code` @owner\u202e'
        output = reports([issue(model=evil)])
        md = output["2026-09.md"].decode()
        self.assertNotIn("<script>", md)
        self.assertNotIn("[click]", md)
        self.assertNotIn("@owner", md)
        self.assertNotIn("\u202e", md)
        self.assertIn("&lt;script&gt;", md)
        self.assertIn("&#124;", md)
        self.assertIn("&#91;click&#93;", md)

    def test_csv_formula_prefix_is_neutralized_in_all_generated_csvs(self):
        for value in ("=HYPERLINK(1)", "+cmd", "-cmd", "@cmd", "\t=cmd", "\ufeff=cmd"):
            with self.subTest(value=value):
                output = reports([issue(model=value)])
                for name in ("2026-09.csv", "2026-09-groups.csv"):
                    self.assertTrue(csv_rows(output[name])[0]["model"].startswith("'"))
        self.assertEqual("vivo + phone", feedback.csv_cell("vivo + phone"))

    def test_csv_quoting_and_newlines_remain_one_record(self):
        output = reports([issue(model='Phone, "special"\nnext line')])
        rows = csv_rows(output["2026-09.csv"])
        self.assertEqual(1, len(rows))
        self.assertEqual('Phone, "special" next line', rows[0]["model"])

    def test_untrusted_supplied_urls_cannot_change_report_links(self):
        output = reports([issue(html_url="javascript:alert(1)")])
        self.assertEqual(f"https://github.com/{REPO}/issues/1", csv_rows(output["2026-09.csv"])[0]["url"])
        self.assertNotIn("javascript:", output["2026-09.md"].decode())

    def test_success_writes_three_reports_and_leaves_other_files_alone(self):
        reader, _ = client([response([issue()]), response([])])
        with tempfile.TemporaryDirectory() as directory:
            other = Path(directory) / "other.md"
            other.write_text("leave this alone", encoding="utf-8")
            names = feedback.run(reader, REPO, directory, now=NOW)
            self.assertEqual(["2026-09-groups.csv", "2026-09.csv", "2026-09.md"], names)
            self.assertEqual("leave this alone", other.read_text(encoding="utf-8"))
            self.assertFalse(any(path.suffix == ".tmp" for path in Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
