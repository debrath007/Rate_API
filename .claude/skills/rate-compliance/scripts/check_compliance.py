#!/usr/bin/env python3
"""Check an APR rate spreadsheet against company rate policy.

Standalone: the only third-party dependency is openpyxl. Copy this script (or the
whole skill folder it lives in) anywhere and point it at a workbook.

    python check_compliance.py --file APR_Report.xlsx
    python check_compliance.py --file APR_Report.xlsx --format text

Exit codes:
    0  compliant
    1  violations found
    2  could not run (missing file, missing column, unreadable sheet)

The 0/1 split makes this usable directly as a CI gate.

The policy itself is documented in ../SKILL.md; this script is its executable form.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone

try:
    from openpyxl import load_workbook
except ImportError:  # pragma: no cover - environment problem, not logic
    print("error: openpyxl is required. Install it with: pip install openpyxl",
          file=sys.stderr)
    sys.exit(2)


# --- Policy constants --------------------------------------------------------

SCRA = "SCRA"
SCRA_REQUIRED_RATE = 6.00

# The most a card member may be charged, as a matter of company policy.
MAX_APR = 29.99

# Rates are held to two decimals, so compare with a tolerance. Exact float
# equality would manufacture violations out of representation error.
EPSILON = 0.005

RULE_SCRA = "SCRA_FIXED_RATE"
RULE_MAX_APR = "MAX_APR_CAP"
RULE_LOWER_WINS = "LOWER_RATE_WINS"

SEVERITY_CRITICAL = "CRITICAL"
SEVERITY_HIGH = "HIGH"

EXIT_COMPLIANT = 0
EXIT_VIOLATIONS = 1
EXIT_ERROR = 2


# --- Column handling ---------------------------------------------------------

# Logical name -> accepted header spellings (normalised: lowercased, non-alphanumerics
# stripped). Header order in the sheet does not matter.
COLUMN_ALIASES = {
    "accountId": ("accountid", "account", "accountnumber", "acctid"),
    "rateType": ("ratetype", "type", "ratecode"),
    "rate": ("rate", "normalrate", "apr", "normalapr"),
    "overrideCode": ("overridecode", "override", "code"),
    "overrideRate": ("overriderate", "overrideapr"),
}

REQUIRED_COLUMNS = tuple(COLUMN_ALIASES)


class CheckError(Exception):
    """Something made the check impossible to run. Distinct from a violation."""


def _normalise(header) -> str:
    if header is None:
        return ""
    return "".join(ch for ch in str(header).lower() if ch.isalnum())


def resolve_columns(header_row) -> dict[str, int]:
    """Map logical column names to 0-based indices by matching header text.

    Raises CheckError naming the missing column and listing what was found, so a
    layout mismatch fails loudly instead of silently reading the wrong column.
    """
    seen = {_normalise(cell): idx for idx, cell in enumerate(header_row)}
    resolved: dict[str, int] = {}

    for logical, aliases in COLUMN_ALIASES.items():
        for alias in aliases:
            if alias in seen:
                resolved[logical] = seen[alias]
                break

    missing = [name for name in REQUIRED_COLUMNS if name not in resolved]
    if missing:
        found = [str(h) for h in header_row if h is not None]
        raise CheckError(
            "could not find required column(s): " + ", ".join(missing)
            + ". Headers found in the sheet: " + (", ".join(found) or "(none)")
        )
    return resolved


def _is_percent_formatted(cell) -> bool:
    fmt = getattr(cell, "number_format", None)
    return bool(fmt) and "%" in str(fmt)


def read_rate(cell):
    """Return a rate as a percentage number (6.0 means 6%), or None if blank.

    A percent-formatted cell stores 0.06 for 6%, while a plain-number column may
    store 6.0 directly. Decide from the cell's number format rather than guessing
    from magnitude -- a genuine 0.5% rate would defeat any magnitude heuristic.
    """
    if cell is None or cell.value is None:
        return None
    value = cell.value
    if isinstance(value, str):
        value = value.strip().rstrip("%")
        if not value:
            return None
        try:
            return float(value)
        except ValueError as exc:
            raise CheckError(f"could not read {cell.coordinate!r} as a rate: {cell.value!r}") from exc
    if not isinstance(value, (int, float)):
        raise CheckError(f"could not read {cell.coordinate!r} as a rate: {value!r}")
    return float(value) * 100.0 if _is_percent_formatted(cell) else float(value)


def read_text(cell):
    if cell is None or cell.value is None:
        return None
    text = str(cell.value).strip()
    return text or None


# --- Policy evaluation -------------------------------------------------------

def effective_rate(rate: float, override_code, override_rate) -> float:
    """What the customer is actually charged."""
    if not override_code or override_rate is None:
        return rate
    return min(rate, override_rate)


def _round2(value):
    return None if value is None else round(value + 0.0, 2)


def check_scra_fixed_rate(row, violations):
    """SCRA accounts must sit at exactly 6.00%.

    Above the cap is a statutory breach. Below it costs the customer nothing but
    proves the account was not insulated from a portfolio rate adjustment -- the
    same failure would overcharge on the next upward move.
    """
    code = row["overrideCode"]
    if not code or code.upper() != SCRA:
        return

    effective = row["effective"]
    if abs(effective - SCRA_REQUIRED_RATE) <= EPSILON:
        return

    above_cap = effective > SCRA_REQUIRED_RATE
    if above_cap:
        message = (f"SCRA account effective rate is {effective:.2f}%, "
                   f"above the 6.00% statutory cap")
    else:
        message = (f"SCRA account effective rate is {effective:.2f}%, "
                   f"expected exactly 6.00% (account was moved by a rate "
                   f"adjustment it should be immune to)")

    violations.append({
        "rule": RULE_SCRA,
        "severity": SEVERITY_CRITICAL if above_cap else SEVERITY_HIGH,
        "accountId": row["accountId"],
        "rateType": row["rateType"],
        "expected": SCRA_REQUIRED_RATE,
        "actual": _round2(effective),
        "message": message,
    })


def check_max_apr_cap(row, violations):
    """No card member may be charged more than 29.99% APR.

    Judged on the effective rate only: a normal rate above the cap that a lower
    override masks charges the customer the lower value, so nothing is overcharged
    and there is nothing to report.
    """
    effective = row["effective"]
    if effective - MAX_APR <= EPSILON:
        return

    violations.append({
        "rule": RULE_MAX_APR,
        "severity": SEVERITY_CRITICAL,
        "accountId": row["accountId"],
        "rateType": row["rateType"],
        "expected": MAX_APR,
        "actual": _round2(effective),
        "message": (f"Effective rate is {effective:.2f}%, above the "
                    f"{MAX_APR:.2f}% maximum APR a card member may be charged"),
    })


def check_lower_rate_wins(row, violations):
    """Where an override exists, the lower of the two rates must be charged."""
    code = row["overrideCode"]
    if not code:
        return

    rate = row["rate"]
    override_rate = row["overrideRate"]

    if override_rate is None:
        violations.append({
            "rule": RULE_LOWER_WINS,
            "severity": SEVERITY_HIGH,
            "accountId": row["accountId"],
            "rateType": row["rateType"],
            "expected": None,
            "actual": _round2(rate),
            "message": (f"Override code {code} is set but no override rate is "
                        f"present, so the full normal rate of {rate:.2f}% is "
                        f"being charged"),
        })
        return

    # Regression guard: effective_rate() takes the minimum by construction, so
    # this cannot fire on this implementation. It exists to catch a system whose
    # served rate disagrees with the policy.
    expected = min(rate, override_rate)
    actual = row["effective"]
    if abs(actual - expected) > EPSILON:
        violations.append({
            "rule": RULE_LOWER_WINS,
            "severity": SEVERITY_CRITICAL,
            "accountId": row["accountId"],
            "rateType": row["rateType"],
            "expected": _round2(expected),
            "actual": _round2(actual),
            "message": (f"Effective rate is {actual:.2f}% but the lower of normal "
                        f"({rate:.2f}%) and override ({override_rate:.2f}%) is "
                        f"{expected:.2f}%"),
        })


# --- Reading and reporting ---------------------------------------------------

def load_rows(path: str, sheet_name: str | None):
    try:
        workbook = load_workbook(path, data_only=True)
    except FileNotFoundError as exc:
        raise CheckError(f"file not found: {path}") from exc
    except Exception as exc:
        raise CheckError(f"could not open {path}: {exc}") from exc

    try:
        worksheet = workbook[sheet_name] if sheet_name else workbook.worksheets[0]
    except KeyError as exc:
        available = ", ".join(workbook.sheetnames)
        raise CheckError(f"no sheet named {sheet_name!r}. Available: {available}") from exc

    rows = list(worksheet.iter_rows())
    if not rows:
        raise CheckError(f"sheet {worksheet.title!r} is empty")

    columns = resolve_columns([cell.value for cell in rows[0]])

    records = []
    for cells in rows[1:]:
        account_id = read_text(cells[columns["accountId"]]) if columns["accountId"] < len(cells) else None
        if account_id is None:
            continue  # trailing blank row

        rate = read_rate(cells[columns["rate"]]) or 0.0
        override_code = read_text(cells[columns["overrideCode"]])
        override_rate = read_rate(cells[columns["overrideRate"]])

        records.append({
            "accountId": account_id,
            "rateType": read_text(cells[columns["rateType"]]),
            "rate": rate,
            "overrideCode": override_code,
            "overrideRate": override_rate,
            "effective": effective_rate(rate, override_code, override_rate),
        })
    return records


def build_report(records) -> dict:
    violations: list[dict] = []
    for row in records:
        # Order matters: it is the order findings are presented in, and matches
        # how the rules are numbered in SKILL.md.
        check_scra_fixed_rate(row, violations)
        check_max_apr_cap(row, violations)
        check_lower_rate_wins(row, violations)

    critical = sum(1 for v in violations if v["severity"] == SEVERITY_CRITICAL)

    return {
        "status": "compliant" if not violations else "violations_found",
        "checkedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "rowsChecked": len(records),
        "accountsChecked": len({r["accountId"] for r in records}),
        "criticalCount": critical,
        "highCount": len(violations) - critical,
        "violations": violations,
        "compliant": not violations,
    }


def render_text(report: dict) -> str:
    lines = []
    if report["status"] == "compliant":
        lines.append(f"COMPLIANT - {report['rowsChecked']} rows across "
                     f"{report['accountsChecked']} accounts, no policy violations.")
        return "\n".join(lines)

    lines.append(f"NON-COMPLIANT - {len(report['violations'])} violation(s): "
                 f"{report['criticalCount']} critical, {report['highCount']} high "
                 f"(across {report['rowsChecked']} rows, "
                 f"{report['accountsChecked']} accounts).")
    lines.append("")
    width = max((len(v["accountId"]) for v in report["violations"]), default=9)
    for v in report["violations"]:
        expected = "-" if v["expected"] is None else f"{v['expected']:.2f}%"
        actual = "-" if v["actual"] is None else f"{v['actual']:.2f}%"
        lines.append(f"  {v['severity']:<8} {v['accountId']:<{width}} "
                     f"rate type {v['rateType']:<4} {v['rule']}")
        lines.append(f"           expected {expected}, actual {actual}")
        lines.append(f"           {v['message']}")
        lines.append("")
    return "\n".join(lines).rstrip()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Check an APR rate spreadsheet against company rate policy.")
    parser.add_argument("--file", required=True,
                        help="path to the .xlsx workbook to check")
    parser.add_argument("--sheet", default=None,
                        help="sheet name (default: the first sheet)")
    parser.add_argument("--format", choices=("json", "text"), default="json",
                        help="output format (default: json)")
    args = parser.parse_args(argv)

    try:
        records = load_rows(args.file, args.sheet)
        report = build_report(records)
    except CheckError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_ERROR

    if args.format == "json":
        print(json.dumps(report, indent=2))
    else:
        print(render_text(report))

    return EXIT_COMPLIANT if report["compliant"] else EXIT_VIOLATIONS


if __name__ == "__main__":
    sys.exit(main())
