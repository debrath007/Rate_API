"""One test case per product requirement, bucketed by triggering event.

This is a *traceability* suite, not a behavioural one. For each of the 188
requirements it answers: is this requirement wired to a live, enabled rule, and
does the dataset actually contain a row that exercises it? The behaviour of each
rule -- boundaries, severities, message wording -- is covered in
test_check_compliance.py.

Requirements the current system cannot execute are skipped, each naming what it is
blocked on, so the suite reports honest coverage rather than silently omitting them:

    needs_simulator     no payment ledger, billing cycle, or job scheduler exists
    needs_schema        the workbook would need extra columns
    not_data_testable   needs documents or comms records, not spreadsheet data

    pytest test_requirements.py
    pytest test_requirements.py -k RATE_ADJUSTMENT      # one event bucket
    pytest test_requirements.py -m implemented          # only the runnable ones
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

import check_compliance as cc  # noqa: E402

SKILL_DIR = Path(__file__).resolve().parent.parent
CATALOGUE = json.loads((SKILL_DIR / "requirements.json").read_text(encoding="utf-8"))
REQUIREMENTS = CATALOGUE["requirements"]
RULES = cc.load_rules()

WORKBOOK = SKILL_DIR.parent.parent.parent / "src/main/resources/APR_Report.xlsx"

SKIP_REASONS = {
    "needs_simulator": "no simulator for this event",
    "needs_schema": "workbook lacks the columns",
    "not_data_testable": "not answerable from spreadsheet data",
}


def _ids(reqs):
    return [f"{r['id']}-{r['event']}" for r in reqs]


@pytest.fixture(scope="session")
def rows():
    if not WORKBOOK.is_file():
        pytest.skip(f"workbook not found at {WORKBOOK}")
    return cc.load_rows(str(WORKBOOK), None)


# --- one case per requirement ------------------------------------------------

@pytest.mark.parametrize("req", REQUIREMENTS, ids=_ids(REQUIREMENTS))
def test_requirement(req, rows):
    """Each requirement either traces to an enabled rule, or says why it cannot."""
    if req["status"] != "implemented":
        pytest.skip(f"{req['status']}: {req['blockedOn']} "
                    f"({SKIP_REASONS.get(req['status'], '')})")

    assert req["rules"], f"{req['id']} is implemented but names no rule"

    for rule_id in req["rules"]:
        rule = next((r for r in RULES["rules"] if r["id"] == rule_id), None)
        assert rule is not None, (
            f"{req['id']} claims rule {rule_id!r}, which is not in rules.json")

        assert rule.get("enabled", True), (
            f"{req['id']} traces to rule {rule['id']}, which is disabled -- the "
            f"requirement would not be enforced")

        assert req["id"] in rule.get("requirements", []), (
            f"{req['id']} points at rule {rule['id']} but that rule does not cite it back")

        # A rule with nothing in the dataset to exercise it is only nominally covered.
        assert any(_exercises(row, rule) for row in rows), (
            f"no row in the dataset exercises rule {rule['id']} for {req['id']}")


def _exercises(row, rule):
    """Whether this row is a meaningful input to this rule's evaluator."""
    check = rule["check"]
    params = rule["params"]

    if check == "override_code_ceiling":
        codes = params["codeCeilings"]
        return bool(row["overrideCode"]) and any(
            c.strip().upper() in codes for c in row["overrideCode"].split(","))
    if check == "max_apr_cap":
        return row.get("ceilingRate") is not None
    if check == "rate_floor":
        return row.get("floorRate") is not None and not row["overrideCode"]
    if check == "lower_rate_wins":
        return bool(row["overrideCode"])
    if check == "rate_matches_formula":
        return (row.get("rateBasis") or "").upper() == params.get("basis", "VARIABLE")
    if check == "rate_sanity":
        return True
    if check == "config_matches":
        return row.get(params["field"]) is not None
    if check == "override_not_expired":
        return bool(row["overrideCode"]) and row.get("overrideExpiry") is not None
    if check == "protection_dates_valid":
        return _has_protected_code(row, params["protectedCodes"])
    if check == "pre_service_debt_scope":
        return (_has_protected_code(row, params["protectedCodes"])
                and row.get("protectionStart") is not None
                and row.get("originationDate") is not None)
    if check == "bounds_within_product_range":
        return (row.get("productCode") is not None
                and row.get("floorRate") is not None
                and row.get("ceilingRate") is not None)
    return False


def _has_protected_code(row, protected):
    code = row["overrideCode"]
    return bool(code) and any(c.strip().upper() in protected for c in code.split(","))


# --- coverage summary --------------------------------------------------------

def test_catalogue_covers_every_requirement():
    assert len(REQUIREMENTS) == CATALOGUE["totalRequirements"] == 188


def test_every_requirement_has_an_event_and_a_disposition():
    for req in REQUIREMENTS:
        assert req["event"], f"{req['id']} has no event bucket"
        assert req["status"] in {"implemented", "needs_simulator", "needs_schema",
                                 "not_data_testable"}, f"{req['id']} status {req['status']!r}"
        if req["status"] == "implemented":
            assert req["rules"], f"{req['id']} is implemented but names no rule"
        else:
            assert req["blockedOn"], f"{req['id']} is {req['status']} but says nothing about why"


def test_every_rule_is_cited_by_at_least_one_requirement():
    """A rule nothing traces to is either dead or undocumented."""
    cited = {rule_id for r in REQUIREMENTS for rule_id in r["rules"]}
    for rule in RULES["rules"]:
        assert rule["id"] in cited, f"rule {rule['id']} is not cited by any requirement"


def test_no_critical_requirement_traces_to_a_disabled_rule():
    by_id = {r["id"]: r for r in RULES["rules"]}
    for req in REQUIREMENTS:
        if req["status"] == "implemented" and req["priority"] == "Critical":
            for rule_id in req["rules"]:
                assert by_id[rule_id].get("enabled", True), (
                    f"critical requirement {req['id']} traces to disabled rule {rule_id}")


def test_print_coverage(capsys):
    """Not an assertion so much as a report; run with -s to read it."""
    with capsys.disabled():
        by_status = Counter(r["status"] for r in REQUIREMENTS)
        by_event = Counter(r["event"] for r in REQUIREMENTS)
        done = by_status["implemented"]
        print(f"\n  coverage: {done}/{len(REQUIREMENTS)} "
              f"({done * 100 // len(REQUIREMENTS)}%) executable today")
        for status, n in by_status.most_common():
            print(f"    {status:<20} {n:>3}")
        print("  by event:")
        for event, n in by_event.most_common():
            impl = sum(1 for r in REQUIREMENTS
                       if r["event"] == event and r["status"] == "implemented")
            print(f"    {event:<24} {n:>3}   implemented {impl}")
