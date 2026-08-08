---
name: rate-compliance
description: Company policy rules for credit-card APR rates - SCRA accounts pinned at 6% regardless of repo-rate movement, a 29.99% maximum APR any card member may be charged, and lower-of-normal-or-override always served. Use when reviewing a rate compliance report, auditing APR data after a rate deployment, or answering questions about SCRA caps, the maximum APR ceiling, override codes, or which rate a customer should be charged.
---

# Rate Policy Compliance

Company policy governing what APR a credit-card account may be charged. These rules are
binding: a breach is a customer-impacting defect, and in one direction a regulatory one.

## How to get a compliance report

**You have a spreadsheet path** — run the bundled checker yourself:

```bash
python scripts/check_compliance.py --file <path.xlsx>
```

It prints the JSON report described at the end of this file. Exit code `0` means
compliant, `1` means violations were found (both are normal outcomes — read stdout), and
`2` means it could not run, in which case stderr says why. `--format text` gives a
human-readable rendering instead.

**You were handed a report already** — interpret it as given. Do not re-run the checker
and do not recompute anything.

Either way the numbers in the report are authoritative. See "Reading a compliance
report" below.

## Data model

`APR_Report.xlsx` holds one row per (account, rate type):

| Column | Meaning |
|---|---|
| `AccountId` | Account identifier, e.g. `ACC00024` |
| `RateType` | Two-digit product code, `01`–`70` |
| `Rate` | The normal APR for that rate type |
| `Balance` | Balance carried at that rate |
| `OverrideCode` | Blank, `SCRA`, or `CMA` |
| `OverrideRate` | The override APR; blank when `OverrideCode` is blank |

The **effective rate** is what the customer is actually charged, and is what every rule below
is stated in terms of.

---

## Rule 1 — `SCRA_FIXED_RATE`

**An account with `OverrideCode = SCRA` must have an effective rate of exactly 6.00%.**

The Servicemembers Civil Relief Act caps eligible servicemembers' APR at 6%. Company policy
pins it at exactly 6.00% and treats it as **immune to repo-rate movement**: when rates are
adjusted upward or downward across the portfolio, SCRA accounts must not move. A rate
deployment that shifts a SCRA account has broken this rule, even if the account ends up
*cheaper*.

Severity depends on direction, because the two mean very different things:

| Condition | Severity | Why |
|---|---|---|
| effective rate **> 6.00%** | `CRITICAL` | Exceeds the statutory cap. The customer is being overcharged in breach of federal law. Remediate immediately and consider whether refunds are owed. |
| effective rate **< 6.00%** | `HIGH` | The account was moved by a deployment it should have been insulated from. No customer harm, but the immunity control has failed — and the same failure would overcharge on an upward adjustment. |

### Worked examples

Compliant — the override pins the account at 6.00%:
```
ACC00024 | 55 | Rate 24.77% | SCRA | OverrideRate 6.00%  -> effective 6.00%   OK
```

Violation after a −5% portfolio adjustment scaled the SCRA override along with everything else:
```
ACC00024 | 55 | Rate 24.77% | SCRA | OverrideRate 5.70%  -> effective 5.70%   HIGH
```
Expected `6.00`, actual `5.70`. The account should not have moved at all.

---

## Rule 2 — `MAX_APR_CAP`

**No card member may be charged more than 29.99% APR.**

A hard company ceiling on the effective rate, independent of product, rate type, or how the
account got there. Unlike the SCRA cap this is policy rather than statute, but it is still an
overcharge when breached, so it carries the same severity.

| Condition | Severity | Why |
|---|---|---|
| effective rate **> 29.99%** | `CRITICAL` | The customer is being charged above the maximum the company permits. Direct billing defect — remediate and consider whether refunds are owed. |

The cap is judged on the **effective rate only**. A normal rate above 29.99% that a lower
override masks charges the customer the lower value, so nothing is overcharged and there is
nothing to report.

### Worked examples

Compliant — sits exactly on the ceiling, which is permitted:
```
ACC00007 | 70 | Rate 29.99% | (none) | -> effective 29.99%   OK
```

Compliant — the normal rate is over the cap but the override is what is charged:
```
ACC00012 | 44 | Rate 35.00% | CMA | OverrideRate 20.00% -> effective 20.00%   OK
```

Violation after an upward portfolio adjustment pushed the rate past the ceiling:
```
ACC00007 | 70 | Rate 31.49% | (none) | -> effective 31.49%   CRITICAL
```
Expected at most `29.99`, actual `31.49`.

Violation where the override is the lower rate but still breaches the cap:
```
ACC00018 | 61 | Rate 40.00% | CMA | OverrideRate 32.00% -> effective 32.00%   CRITICAL
```

---

## Rule 3 — `LOWER_RATE_WINS`

**Where an account has both a normal rate and an override rate, the lower of the two must be
the effective rate.** An override exists to benefit the customer; it must never result in a
higher charge, and a declared override must never be silently ignored.

| Condition | Severity | Why |
|---|---|---|
| effective rate ≠ `min(Rate, OverrideRate)` | `CRITICAL` | The system is charging something other than the lower rate. Direct customer-billing defect. |
| `OverrideCode` present but `OverrideRate` blank | `HIGH` | An override was declared with no rate behind it, so the customer falls back to the full normal rate — the benefit they qualified for is not being applied. |

### Worked examples

Compliant — the override is lower, so it is served:
```
ACC00019 | 22 | Rate 13.29% | CMA | OverrideRate 6.88%  -> effective 6.88%   OK
```

Compliant — the override is *higher*, so the normal rate is served. The lower value wins
either way; the override being ineffective is not itself a breach:
```
ACC00002 | 03 | Rate 8.00%  | CMA | OverrideRate 9.00%  -> effective 8.00%   OK
```

Violation — override declared, no rate supplied:
```
ACC00031 | 12 | Rate 18.40% | CMA | OverrideRate (blank) -> effective 18.40%  HIGH
```

---

## Reading a compliance report

Both `scripts/check_compliance.py` and the application's `GET /api/compliance/check`
return the same shape — the endpoint is a thin wrapper around this script, so there is
one implementation of these rules, not two:

```json
{
  "status": "violations_found",
  "checkedAt": "2026-08-07T22:31:00Z",
  "rowsChecked": 154,
  "accountsChecked": 50,
  "criticalCount": 0,
  "highCount": 12,
  "violations": [
    {
      "rule": "SCRA_FIXED_RATE",
      "severity": "HIGH",
      "accountId": "ACC00024",
      "rateType": "55",
      "expected": 6.00,
      "actual": 5.70,
      "message": "SCRA account effective rate is 5.70%, expected exactly 6.00%"
    }
  ]
}
```

`status` is `compliant` when `violations` is empty, otherwise `violations_found`.

**The numbers in the report are authoritative.** They are computed row by row from the
spreadsheet by the application, not estimated. Do not recount rows, re-derive percentages, or
second-guess the arithmetic — reason about what the findings *mean* and what should be done
about them.

## Known systemic cause

*Applies to the APR platform this skill originally shipped with; ignore it if you are
auditing a different system.*

The bulk adjust endpoint (`POST /api/rates/adjust`) has two defects, and most findings after
a rate deployment trace back to one of them. Name the cause rather than listing each account
as its own incident.

**It scales SCRA overrides along with everything else.** SCRA accounts are supposed to be
immune to portfolio movement, so every run knocks them off 6.00%. A cluster of
`SCRA_FIXED_RATE` findings at `HIGH` right after a deployment is this, not 12 independent
data problems.

**It applies no ceiling.** Nothing clamps the result at 29.99%, so an upward adjustment
pushes any account already near the top over the cap. Accounts sitting exactly on 29.99%
breach it on *any* positive adjustment. A cluster of `MAX_APR_CAP` findings after an upward
deployment is this single missing clamp.

These are separate defects with separate fixes — an upward adjustment can produce both sets
of findings at once, and they should not be conflated into one root cause.

## Portability

This skill folder is self-contained. `README.md` covers copying it into another project,
the spreadsheet columns it expects, and how it decides whether a rate cell means `0.06` or
`6.0`. The only third-party dependency is `openpyxl`.
