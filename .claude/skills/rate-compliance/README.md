# rate-compliance

A self-contained rate-policy compliance bundle: the policy, an executable check, and an
SME agent that interprets the results. Nothing here depends on the application it
currently ships with.

## What's in the box

```
rate-compliance/
  SKILL.md                        the policy, and how the agent should apply it
  README.md                       this file
  rules.json                      thresholds, severities and messages -- the tunable policy
  scripts/check_compliance.py     the evaluation logic
  scripts/test_check_compliance.py
```

## Using it in another project

1. Copy the `rate-compliance/` folder into that project's `.claude/skills/`.
2. Copy `.claude/agents/rate-policy-sme.md` alongside it if you want the SME agent too.
3. `pip install openpyxl` — the only third-party dependency.

That's the whole install. There is no build step and nothing to configure.

## Running the check

```bash
python scripts/check_compliance.py --file APR_Report.xlsx
python scripts/check_compliance.py --file APR_Report.xlsx --format text
python scripts/check_compliance.py --file Book.xlsx --sheet "Q3 Rates"
```

Exit codes make it usable as a CI gate directly:

| Code | Meaning |
|---|---|
| `0` | Compliant |
| `1` | Violations found |
| `2` | Could not run — missing file, missing column, unreadable sheet |

```yaml
# Fails the build on any policy violation
- run: python .claude/skills/rate-compliance/scripts/check_compliance.py --file rates.xlsx
```

## What your spreadsheet needs

One row per (account, rate type), with a header row. Required columns, matched **by name**
— order does not matter, and matching ignores case, spaces, and underscores:

| Logical column | Accepted headers |
|---|---|
| `accountId` | AccountId, Account, AccountNumber, AcctId |
| `rateType` | RateType, Type, RateCode |
| `rate` | Rate, NormalRate, APR, NormalAPR |
| `overrideCode` | OverrideCode, Override, Code |
| `overrideRate` | OverrideRate, OverrideAPR |

A missing column exits `2` and tells you which one, listing the headers it did find. It
will not guess.

### How rates are read

A cell formatted as a percentage stores `0.06` for 6%; a plain-number column may store
`6.0` directly. The script decides from the cell's **number format** — if the format
contains `%`, the value is multiplied by 100; otherwise it is taken as-is.

This is the assumption most likely to bite on an unfamiliar spreadsheet. If your rates
come out 100× too large or small, check whether the cells are percent-formatted. A
magnitude heuristic was deliberately avoided: it would misread a genuine 0.5% rate.

## The rules

Full statements, severities, and worked examples are in [SKILL.md](SKILL.md).

- **`SCRA_FIXED_RATE`** — SCRA accounts must be at exactly 6.00%. Above the cap is
  `CRITICAL` (statutory breach); below is `HIGH` (the account was moved by an adjustment
  it should be immune to).
- **`MAX_APR_CAP`** — no card member may be charged above 29.99%. Judged on the effective
  rate, so a high normal rate masked by a lower override is not a breach. `CRITICAL`.
- **`LOWER_RATE_WINS`** — where both a normal and an override rate exist, the lower must
  be charged. An override code with no rate behind it is `HIGH`.

Rates are compared with a tolerance of `0.005` because values are held to two decimals.

### Changing the policy

Those numbers are **not** in the script. They live in [rules.json](rules.json), which is
read at every run:

```jsonc
{
  "epsilon": 0.005,
  "rules": [
    {
      "id": "SCRA_FIXED_RATE",
      "enabled": true,
      "check": "scra_fixed_rate",         // selects the evaluator, see below
      "params":   { "overrideCode": "SCRA", "requiredRate": 6.0 },
      "severity": { "above": "CRITICAL", "below": "HIGH" },
      "messages": { "above": "...{actual:.2f}...", "below": "..." }
    }
  ]
}
```

| To do this | Edit |
|---|---|
| Retune a threshold | `params` (e.g. `maxRate`, `requiredRate`) |
| Re-grade a finding | `severity` |
| Reword a finding | `messages` — `{}` placeholders are filled per rule |
| Turn a rule off | `"enabled": false` |
| Reorder findings | move the rule in the `rules` array |
| Point at a different policy | `--rules /path/to/other.json` |

`check` selects one of the evaluators defined in the script — `scra_fixed_rate`,
`max_apr_cap`, `lower_rate_wins`. A rule file names an evaluator; it never carries
executable logic, so it can retune the policy but cannot introduce new behaviour. An
unknown name fails with the list of valid ones.

A missing, malformed, or invalid rule file exits `2` — the same as any other reason the
check could not run. It never silently falls back to built-in defaults, because a typo in
an edit would then look like it had taken effect.

Each report echoes `rulesVersion` and `rulesApplied` so a stored result records which
policy produced it.

## Output

```json
{
  "status": "violations_found",
  "checkedAt": "2026-08-07T23:12:00Z",
  "rowsChecked": 154,
  "accountsChecked": 50,
  "criticalCount": 0,
  "highCount": 8,
  "violations": [
    {
      "rule": "SCRA_FIXED_RATE",
      "severity": "HIGH",
      "accountId": "ACC00024",
      "rateType": "55",
      "expected": 6.0,
      "actual": 5.7,
      "message": "SCRA account effective rate is 5.70%, expected exactly 6.00% (...)"
    }
  ],
  "compliant": false
}
```

## Tests

```bash
pytest scripts/test_check_compliance.py
```

Fixtures are generated at runtime, so there is no binary test file to keep in sync and
the suite runs anywhere the script does.
