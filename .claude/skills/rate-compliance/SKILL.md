---
name: rate-compliance
description: Company policy rules for credit-card APR rates - SCRA capped at 6% and MLA at 36% as ceilings immune to benchmark movement, each account held inside its own disclosed floor and ceiling, variable rates derived from index plus margin, and lower-of-normal-or-override always served. Use when reviewing a rate compliance report, auditing APR data after a repricing run, or answering questions about SCRA or MLA caps, rate floors and ceilings, override codes, or which rate a customer should be charged.
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

The thresholds and severities below are held in `rules.json` beside this file and read at
every run, so a report reflects whatever that file said at the time. Every report echoes
`rulesVersion` and `rulesApplied`; if either differs from what this document describes,
**the report is authoritative and this document is stale** — say so rather than reasoning
from the numbers written here.

**You were handed a report already** — interpret it as given. Do not re-run the checker
and do not recompute anything.

Either way the numbers in the report are authoritative. See "Reading a compliance
report" below.

## Data model

`APR_Report.xlsx` holds one row per (account, rate type):

| Column | Meaning |
|---|---|
| `AccountId` | Account identifier, e.g. `ACC005` |
| `RateType` | Two-digit product code |
| `Scenario` | The case this row exists to exercise, e.g. `SCRA_AT_CAP` |
| `RateBasis` | `VARIABLE` tracks the index; `FIXED` does not move when the index does |
| `Index` | The benchmark rate; blank on fixed-rate rows |
| `Margin` | The issuer's spread over the index; blank on fixed-rate rows |
| `Rate` | The normal APR — for variable rows, `Index + Margin` |
| `FloorRate` | Lowest APR the agreement permits |
| `CeilingRate` | Highest APR the agreement permits |
| `OverrideCode` | Blank, or one or more of `SCRA`, `MLA`, `CMA` (comma separated) |
| `OverrideRate` | The override APR; blank when `OverrideCode` is blank |

Because the rate is derived rather than stored in isolation, a benchmark movement is
modelled as a change to `Index`, with variable rows repriced from their own margin.
Fixed-rate rows do not move.

The **effective rate** is what the customer is actually charged, and is what every rule below
is stated in terms of.

---

## Rule 1 — `OVERRIDE_CODE_CEILING`

**An account carrying a protected-population override code must not be charged above that
code's ceiling.**

| Code | Ceiling | Source |
|---|---|---|
| `SCRA` | 6.00% | Servicemembers Civil Relief Act, 50 U.S.C. § 3937 |
| `MLA` | 36.00% | Military Lending Act MAPR cap |

Two things follow from it being a **ceiling rather than a fixed rate**:

- An account whose normal formula lands *below* the ceiling keeps the lower rate, and that
  is correct, not a violation. The protection may never be used to *raise* a rate that
  would otherwise be lower — including below an already-cheaper promotional rate.
- Where several codes apply to one row, the **most protective — lowest — ceiling governs**.
  A servicemember who is also an MLA-covered borrower is held to 6.00%, not 36.00%. This is
  not a conflict; it is the most-protective-standard rule.

Breaching the ceiling is `CRITICAL` in every case: the customer is being overcharged in
breach of a federal statute. Remediate immediately and consider whether refunds are owed.

### Worked examples

Compliant — the override pins the account at the cap:
```
ACC005 | 09 | Rate 24.77% | SCRA | OverrideRate 6.00%  -> effective 6.00%   OK
```

Compliant — the formula lands under the cap, so the customer keeps the lower rate:
```
ACC006 | 11 | Rate 4.50%  | SCRA | OverrideRate 6.00%  -> effective 4.50%   OK
```

Violation after an index rise dragged the override up with it:
```
ACC005 | 09 | Rate 25.27% | SCRA | OverrideRate 6.50%  -> effective 6.50%   CRITICAL
```
Expected at most `6.00`, actual `6.50`. The account should not have moved at all.

Violation where two protections apply and the stricter one was not used:
```
ACC009 | 14 | Rate 25.27% | SCRA,MLA | OverrideRate 10.00% -> effective 10.00%  CRITICAL
```
Expected at most `6.00` — SCRA governs, not MLA's 36.00%.

---

## Rule 2 — `MAX_APR_CAP`

**No card member may be charged more than the ceiling disclosed in their own agreement.**

Read per row from `CeilingRate`, so accounts on different products are judged against
different numbers rather than one global constant. Where the column is absent the rule
falls back to the configured default.

Judged on the **effective rate only**. A normal rate above the cap that a lower override
masks charges the customer the lower value, so nothing is overcharged and there is nothing
to report. `CRITICAL` when breached — a direct billing defect.

### Worked examples

Compliant — sits exactly on the ceiling, which is permitted:
```
ACC002 | 03 | Rate 29.99% | (none) | Ceiling 29.99% -> effective 29.99%   OK
```

Compliant — the normal rate is over the cap but the override is what is charged:
```
ACC007 | 12 | Rate 35.00% | CMA | OverrideRate 20.00% -> effective 20.00%   OK
```

Violation after an index rise pushed the rate past the ceiling:
```
ACC002 | 03 | Rate 30.49% | (none) | Ceiling 29.99% -> effective 30.49%   CRITICAL
```

An account sitting *exactly* on its ceiling breaches on **any** upward movement. That is
the expected consequence of a missing clamp, not an unlucky coincidence.

---

## Rule 3 — `RATE_FLOOR`

**A variable rate must be clamped at the floor its agreement discloses, however far the
index falls.**

`HIGH`: the customer is being *undercharged*, so there is no consumer harm — but the same
missing clamp is what lets an upward movement breach the ceiling, and revenue is leaking
in the meantime.

Rows carrying an override are **exempt**. An override is a legitimate reason to sit below
the floor; applying the floor there would flag every SCRA and goodwill account.

```
ACC003 | 05 | Index 0.50% | Margin 9.49% | Rate 9.99% | Floor 9.99%  -> OK
ACC003 | 05 | Index 0.00% | Margin 9.49% | Rate 9.49% | Floor 9.99%  -> HIGH
```

---

## Rule 4 — `LOWER_RATE_WINS`

**Where an account has both a normal rate and an override rate, the lower of the two must
be the effective rate.** An override exists to benefit the customer; it must never result
in a higher charge, and a declared override must never be silently ignored.

| Condition | Severity | Why |
|---|---|---|
| effective rate ≠ `min(Rate, OverrideRate)` | `CRITICAL` | The system is charging something other than the lower rate. Direct billing defect. |
| `OverrideCode` present but `OverrideRate` blank | `HIGH` | An override was declared with no rate behind it, so the customer falls back to the full normal rate — the benefit they qualified for is not being applied. |

### Worked examples

Compliant — the override is lower, so it is served:
```
ACC013 | 21 | Rate 23.25% | CMA | OverrideRate 11.99% -> effective 11.99%   OK
```

Compliant — the override is *higher*, so the normal rate is served. The lower value wins
either way; the override being ineffective is not itself a breach:
```
ACC010 | 15 | Rate 8.00%  | CMA | OverrideRate 9.00%  -> effective 8.00%   OK
```

Violation — override declared, no rate supplied:
```
ACC007 | 12 | Rate 18.40% | CMA | OverrideRate (blank) -> effective 18.40%  HIGH
```

---

## Rule 5 — `RATE_MATCHES_FORMULA`

**For a variable-rate account the stored APR must equal `Index + Margin`, rounded to the
disclosed precision.**

Checked against the **normal rate**, not the effective one: an override changes what is
charged, not how the underlying rate is derived. Skipped entirely on fixed-rate rows and
on any sheet without `Index`/`Margin` columns.

`HIGH`. A drift means the charged rate no longer matches the method that was disclosed —
so the disclosure and the billing have diverged, even if the number still looks plausible.

```
ACC001 | 01 | Index 8.25% | Margin 14.74% | Rate 22.99%  -> OK
ACC001 | 01 | Index 8.25% | Margin 14.74% | Rate 23.05%  -> HIGH (derives 22.99)
```

---

## Rule 6 — `RATE_SANITY`

**No negative rate, and nothing beyond any plausible ceiling, may reach the rate table.**

`CRITICAL` in both directions. This is deliberately separate from the policy ceilings
because it means something different: a bad index value, a null read as zero, or an
uninitialised variable — a data-integrity failure rather than a pricing decision. Read a
`RATE_SANITY` finding as "the feed or the calculation is broken", not "this customer was
overcharged by policy".

```
ACC001 | 01 | Rate -4.00%   -> CRITICAL  (negative)
ACC001 | 01 | Rate 150.00%  -> CRITICAL  (beyond the sanity ceiling)
```

## Reading a compliance report

Both `scripts/check_compliance.py` and the application's `GET /api/compliance/check`
return the same shape — the endpoint is a thin wrapper around this script, so there is
one implementation of these rules, not two:

```json
{
  "status": "violations_found",
  "checkedAt": "2026-08-09T04:12:00Z",
  "rowsChecked": 24,
  "accountsChecked": 14,
  "criticalCount": 8,
  "highCount": 0,
  "rulesVersion": "2.0",
  "rulesApplied": ["OVERRIDE_CODE_CEILING", "MAX_APR_CAP", "RATE_FLOOR",
                   "LOWER_RATE_WINS", "RATE_MATCHES_FORMULA", "RATE_SANITY"],
  "violations": [
    {
      "rule": "OVERRIDE_CODE_CEILING",
      "severity": "CRITICAL",
      "accountId": "ACC005",
      "rateType": "09",
      "scenario": "SCRA_AT_CAP",
      "expected": 6.00,
      "actual": 6.50,
      "message": "SCRA account effective rate is 6.50%, above the 6.00% cap for that protection"
    }
  ]
}
```

`status` is `compliant` when `violations` is empty, otherwise `violations_found`.

Each finding carries the `scenario` of the row it came from, so a violation can be traced
back to the case that row was put there to exercise. `rulesApplied` lists the rules that
actually ran — a rule disabled in `rules.json` will not appear, and its absence explains a
finding you might otherwise have expected.

**The numbers in the report are authoritative.** They are computed row by row from the
spreadsheet by the application, not estimated. Do not recount rows, re-derive percentages, or
second-guess the arithmetic — reason about what the findings *mean* and what should be done
about them.

## Known systemic cause

*Applies to the APR platform this skill originally shipped with; ignore it if you are
auditing a different system.*

The repricing endpoint (`POST /api/rates/adjust`, which moves the benchmark index) has two
defects, and most findings after a rate movement trace back to one of them. Name the cause
rather than listing each account as its own incident.

**It applies the index change to protected override rates.** A `SCRA` or `MLA` override is
a statutory ceiling, not an index-linked rate, so it must not move when the benchmark does.
This implementation adds the change to every override rate, so an upward movement lifts
protected accounts straight through their cap. A cluster of `OVERRIDE_CODE_CEILING`
findings right after a repricing run is this one defect, not a dozen independent data
problems.

**It applies no clamp.** Nothing holds the result inside `FloorRate`/`CeilingRate`, so an
upward movement pushes any account already near the top over its ceiling, and a downward
one drops accounts through their floor. An account sitting exactly on its ceiling breaches
on *any* rise. A cluster of `MAX_APR_CAP` or `RATE_FLOOR` findings is this single missing
clamp.

These are separate defects with separate fixes — one movement can produce both sets of
findings at once, and they should not be conflated into one root cause.

Two things that are working correctly, and are worth saying so rather than reporting:
fixed-rate rows do not move with the index, and a protected account whose formula lands
*below* its ceiling keeps the lower rate. Neither is a finding.

## Portability

This skill folder is self-contained. `README.md` covers copying it into another project,
the spreadsheet columns it expects, and how it decides whether a rate cell means `0.06` or
`6.0`. The only third-party dependency is `openpyxl`.
