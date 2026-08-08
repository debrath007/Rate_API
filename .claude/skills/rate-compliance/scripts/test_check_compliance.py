"""Tests for check_compliance.py.

Fixtures are built on the fly with openpyxl into tmp_path, so there is no binary
test file to keep in sync and the suite runs anywhere the script does.

    pytest test_check_compliance.py
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
from openpyxl import Workbook

sys.path.insert(0, str(Path(__file__).parent))

import check_compliance as cc  # noqa: E402

SCRIPT = Path(__file__).parent / "check_compliance.py"

DEFAULT_HEADERS = ["AccountId", "RateType", "Rate", "Balance", "OverrideCode", "OverrideRate"]


def write_workbook(path, rows, headers=None, as_percent=True):
    """rows: (account, rateType, rate%, balance, overrideCode, overrideRate%)."""
    wb = Workbook()
    ws = wb.active
    ws.title = "APR Report"
    for col, header in enumerate(headers or DEFAULT_HEADERS, start=1):
        ws.cell(row=1, column=col, value=header)

    for r, (acct, rt, rate, bal, code, orate) in enumerate(rows, start=2):
        ws.cell(row=r, column=1, value=acct)
        ws.cell(row=r, column=2, value=rt)
        cell = ws.cell(row=r, column=3,
                       value=(rate / 100 if as_percent else rate) if rate is not None else None)
        if as_percent:
            cell.number_format = "0.00%"
        ws.cell(row=r, column=4, value=bal)
        ws.cell(row=r, column=5, value=code)
        cell = ws.cell(row=r, column=6,
                       value=(orate / 100 if as_percent else orate) if orate is not None else None)
        if as_percent:
            cell.number_format = "0.00%"
    wb.save(path)
    return path


def check(path, sheet=None):
    return cc.build_report(cc.load_rows(str(path), sheet))


def rules(report, rule):
    return [v for v in report["violations"] if v["rule"] == rule]


# --- The two policy rules ----------------------------------------------------

def test_clean_sheet_is_compliant(tmp_path):
    path = write_workbook(tmp_path / "clean.xlsx", [
        ("ACC1", "01", 10.00, 500.0, None, None),
        ("ACC1", "02", 12.00, 300.0, "SCRA", 6.00),
        ("ACC2", "03", 8.00, 200.0, "CMA", 9.00),
        ("ACC2", "04", 15.00, 400.0, "CMA", 10.00),
    ])
    report = check(path)

    assert report["status"] == "compliant"
    assert report["violations"] == []
    assert report["rowsChecked"] == 4
    assert report["accountsChecked"] == 2


def test_scra_exactly_six_is_not_flagged(tmp_path):
    path = write_workbook(tmp_path / "ok.xlsx", [("ACC1", "01", 24.77, 1.0, "SCRA", 6.00)])
    assert rules(check(path), cc.RULE_SCRA) == []


def test_scra_drifted_below_six_is_high(tmp_path):
    path = write_workbook(tmp_path / "low.xlsx", [("ACC1", "01", 24.77, 1.0, "SCRA", 5.70)])

    found = rules(check(path), cc.RULE_SCRA)

    assert len(found) == 1
    assert found[0]["severity"] == cc.SEVERITY_HIGH
    assert found[0]["expected"] == 6.00
    assert found[0]["actual"] == 5.70
    assert "immune" in found[0]["message"]


def test_scra_above_the_cap_is_critical(tmp_path):
    path = write_workbook(tmp_path / "high.xlsx", [("ACC1", "01", 24.77, 1.0, "SCRA", 7.50)])

    found = rules(check(path), cc.RULE_SCRA)

    assert len(found) == 1
    assert found[0]["severity"] == cc.SEVERITY_CRITICAL
    assert found[0]["actual"] == 7.50
    assert "statutory cap" in found[0]["message"]


def test_override_code_without_a_rate_is_reported(tmp_path):
    path = write_workbook(tmp_path / "norate.xlsx", [("ACC1", "01", 18.40, 1.0, "CMA", None)])

    found = rules(check(path), cc.RULE_LOWER_WINS)

    assert len(found) == 1
    assert found[0]["severity"] == cc.SEVERITY_HIGH
    assert found[0]["expected"] is None
    assert found[0]["actual"] == 18.40
    assert "no override rate" in found[0]["message"]


def test_override_higher_than_normal_rate_is_not_a_violation(tmp_path):
    # The lower value is still charged, so policy is satisfied even though the
    # override is doing nothing.
    path = write_workbook(tmp_path / "higher.xlsx", [("ACC1", "01", 8.00, 1.0, "CMA", 9.00)])
    assert check(path)["violations"] == []


def test_scra_without_an_override_rate_trips_both_rules(tmp_path):
    # The customer falls back to the full normal rate, which is both a missing
    # override and a statutory breach.
    path = write_workbook(tmp_path / "both.xlsx", [("ACC1", "01", 11.00, 1.0, "SCRA", None)])

    report = check(path)

    assert [v["rule"] for v in report["violations"]] == [cc.RULE_SCRA, cc.RULE_LOWER_WINS]
    assert report["criticalCount"] == 1
    assert report["highCount"] == 1


def test_blank_override_code_is_ignored_even_with_a_stray_rate(tmp_path):
    path = write_workbook(tmp_path / "stray.xlsx", [("ACC1", "01", 10.00, 1.0, None, 3.00)])
    assert check(path)["violations"] == []


# --- Portability -------------------------------------------------------------

def test_reordered_columns_still_work(tmp_path):
    wb = Workbook()
    ws = wb.active
    for col, header in enumerate(
            ["OverrideRate", "AccountId", "Balance", "OverrideCode", "RateType", "Rate"], start=1):
        ws.cell(row=1, column=col, value=header)
    cell = ws.cell(row=2, column=1, value=0.057)
    cell.number_format = "0.00%"
    ws.cell(row=2, column=2, value="ACC1")
    ws.cell(row=2, column=3, value=1.0)
    ws.cell(row=2, column=4, value="SCRA")
    ws.cell(row=2, column=5, value="01")
    cell = ws.cell(row=2, column=6, value=0.2477)
    cell.number_format = "0.00%"
    path = tmp_path / "reordered.xlsx"
    wb.save(path)

    found = rules(check(path), cc.RULE_SCRA)

    assert len(found) == 1
    assert found[0]["actual"] == 5.70


def test_header_matching_ignores_case_and_spacing(tmp_path):
    path = write_workbook(tmp_path / "headers.xlsx",
                          [("ACC1", "01", 24.77, 1.0, "SCRA", 5.70)],
                          headers=["account id", "RATE TYPE", "Rate", "Balance",
                                   "Override_Code", "override rate"])
    assert len(rules(check(path), cc.RULE_SCRA)) == 1


def test_missing_column_fails_loudly(tmp_path):
    path = write_workbook(tmp_path / "missing.xlsx",
                          [("ACC1", "01", 10.0, 1.0, "SCRA", 6.0)],
                          headers=["AccountId", "RateType", "Rate", "Balance",
                                   "OverrideCode", "Notes"])

    with pytest.raises(cc.CheckError) as exc:
        check(path)

    assert "overrideRate" in str(exc.value)
    assert "Notes" in str(exc.value)  # tells you what it did find


def test_plain_number_rate_column_is_read_as_percent(tmp_path):
    # A sheet storing 5.7 rather than 0.057, with no percent formatting.
    path = write_workbook(tmp_path / "plain.xlsx",
                          [("ACC1", "01", 24.77, 1.0, "SCRA", 5.70)],
                          as_percent=False)

    found = rules(check(path), cc.RULE_SCRA)

    assert len(found) == 1
    assert found[0]["actual"] == 5.70


def test_trailing_blank_rows_are_skipped(tmp_path):
    path = write_workbook(tmp_path / "blanks.xlsx", [
        ("ACC1", "01", 10.00, 1.0, None, None),
        (None, None, None, None, None, None),
    ])
    assert check(path)["rowsChecked"] == 1


def test_named_sheet_can_be_selected(tmp_path):
    wb = Workbook()
    wb.active.title = "Empty"
    wb.active["A1"] = "AccountId"
    target = wb.create_sheet("Rates")
    for col, header in enumerate(DEFAULT_HEADERS, start=1):
        target.cell(row=1, column=col, value=header)
    target.cell(row=2, column=1, value="ACC1")
    target.cell(row=2, column=2, value="01")
    target.cell(row=2, column=3, value=10.0)
    target.cell(row=2, column=5, value=None)
    path = tmp_path / "sheets.xlsx"
    wb.save(path)

    assert check(path, sheet="Rates")["rowsChecked"] == 1


# --- CLI contract ------------------------------------------------------------

def run_cli(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args],
                          capture_output=True, text=True)


def test_cli_exit_code_zero_when_compliant(tmp_path):
    path = write_workbook(tmp_path / "c.xlsx", [("ACC1", "01", 10.0, 1.0, "SCRA", 6.0)])

    result = run_cli("--file", str(path))

    assert result.returncode == 0
    assert json.loads(result.stdout)["status"] == "compliant"


def test_cli_exit_code_one_when_violations_found(tmp_path):
    path = write_workbook(tmp_path / "v.xlsx", [("ACC1", "01", 24.77, 1.0, "SCRA", 5.70)])

    result = run_cli("--file", str(path))

    assert result.returncode == 1
    assert json.loads(result.stdout)["criticalCount"] == 0


def test_cli_exit_code_two_when_it_cannot_run(tmp_path):
    result = run_cli("--file", str(tmp_path / "nope.xlsx"))

    assert result.returncode == 2
    assert "error:" in result.stderr
    assert result.stdout == ""  # nothing parseable on stdout when the run failed


def test_cli_text_format_is_human_readable(tmp_path):
    path = write_workbook(tmp_path / "t.xlsx", [("ACC1", "01", 24.77, 1.0, "SCRA", 5.70)])

    result = run_cli("--file", str(path), "--format", "text")

    assert result.returncode == 1
    assert "NON-COMPLIANT" in result.stdout
    assert "SCRA_FIXED_RATE" in result.stdout
    assert not result.stdout.lstrip().startswith("{")
