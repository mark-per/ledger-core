# ledger-core

An in-memory account ledger core: an append-only journal with booking and value dates, a holds book beside it, an end-of-day fee sweep that re-checks every value day a late entry touched, and daily interest paid once at the end of the window. Kotlin, JUnit 5, nothing else. No web layer, no persistence, no UI, no database.

Companion documents: [DESIGN.md](DESIGN.md) (the model and every decision), [NUMBERS.md](NUMBERS.md) (the constants and why), [AMBIGUITIES.md](AMBIGUITIES.md) (what the brief left open and how it was resolved), [REJECTED.md](REJECTED.md) (criteria refused, approaches abandoned), [WORKLOG.md](WORKLOG.md).

## Run

Needs a JDK 21 on the path. The Gradle wrapper fetches Kotlin and JUnit; nothing else is downloaded.

```bash
./gradlew test          # the suite; it replays E1 to E10 and prints the six-day report once
./gradlew replay        # prints the same report without the test harness
./gradlew knownFailing  # the one test that is expected to fail (see below)
```

`./gradlew test` is green (114 tests, including the seeded stress runs under `stress/`, which add about a second). `./gradlew knownFailing` is red by design; it is excluded from `test` by its JUnit tag.

## Read the output

One block per processing day, one sub-block per account. Day 5 is the interesting one:

```
=== Day 5 (2026-01-09) ===
ACC-001 (AED)
  posted:         E7 DEBIT -620.00 value Day 2 (2026-01-06)
  restated:       Day 2 (2026-01-06) 250.00 -> -395.00
                  Day 3 (2026-01-07) 650.00 -> 5.00
                  Day 4 (2026-01-08) 285.00 -> -385.00
  closing ledger: -410.00
  available:      -410.00
  fees:           OVERDRAFT_FEE -25.00 value Day 2 (2026-01-06), closing before fee -370.00
                  OVERDRAFT_FEE -25.00 value Day 4 (2026-01-08), closing before fee -360.00
                  OVERDRAFT_FEE -25.00 value Day 5 (2026-01-09), closing before fee -385.00
  authorizations: Auth-A SETTLED 185.00 of 200.00
                  Auth-B DECLINED 90.00
  interest:       Day 1 (2026-01-05) 0.10, Day 2 (2026-01-06) 0.00, Day 3 (2026-01-07) 0.00, Day 4 (2026-01-08) 0.00, Day 5 (2026-01-09) 0.00 (provisional)
  notes:          error: E8 declined; available -335.00, would be -425.00 after the Auth-B hold of 90.00, below the overdraft limit of 0.00
ACC-002 (BHD)
  posted:         -
  restated:       -
  closing ledger: 0.000
  available:      0.000
  fees:           -
  authorizations: -
  interest:       Day 1 (2026-01-05) 0.000, Day 2 (2026-01-06) 0.000, Day 3 (2026-01-07) 0.000, Day 4 (2026-01-08) 0.000, Day 5 (2026-01-09) 0.000 (provisional)
  notes:          -
```

- **posted**: entries the ledger learned of today, with the operation they came from and the value date they count from.
- **restated**: earlier days whose closing changed because of something posted today. Printed only when a back-dated entry, or a fee it triggered, moved them.
- **closing ledger**: the sum of every known entry with value date on or before this day, including the day's own fees and, on the last day, the interest paid at the close.
- **available**: closing ledger balance minus active holds. What an authorization is checked against.
- **fees**: overdraft fees and fee reversals booked today, each with the value day it belongs to and the closing balance that day had before its fee.
- **authorizations**: every hold on the account and its state: ACTIVE with its expiry, SETTLED with the settled amount, DECLINED, EXPIRED.
- **interest**: the accrual for each day of the window so far, on today's view of each day's closing. Provisional until Day 6, when the paid amount is printed beside the exact unrounded figure.
- **notes**: errors (an operation that did not do what it asked: a declined authorization, a rejected reversal) and warnings (it did, with a flag: an unmatched settlement, a late arrival, an expired hold).

## The journal for ACC-001 after Day 6

An entry means money moved. Nothing is ever removed.

| # | Booked | Value | Type | Amount | From |
|---|---|---|---|---|---|
| 1 | Day 1 | Day 1 | CREDIT | 1,200.00 | E1 |
| 2 | Day 1 | Day 1 | DEBIT | −950.00 | E2 |
| 3 | Day 3 | Day 3 | CREDIT | 400.00 | E4 |
| 4 | Day 4 | Day 4 | SETTLEMENT | −185.00 | E5, settles Auth-A |
| 5 | Day 4 | Day 4 | UNMATCHED_SETTLEMENT | −180.00 | E6, Auth-Z was never authorized |
| 6 | Day 5 | Day 2 | DEBIT | −620.00 | E7 |
| 7 | Day 5 | Day 2 | OVERDRAFT_FEE | −25.00 | Day 2 closed at −370.00 |
| 8 | Day 5 | Day 4 | OVERDRAFT_FEE | −25.00 | Day 4 closed at −360.00 |
| 9 | Day 5 | Day 5 | OVERDRAFT_FEE | −25.00 | Day 5 closed at −385.00 |
| 10 | Day 6 | Day 2 | REVERSAL | 620.00 | E9, reverses E7 |
| 11 | Day 6 | Day 2 | FEE_REVERSAL | 25.00 | Day 2 now closes at 250.00 |
| 12 | Day 6 | Day 4 | FEE_REVERSAL | 25.00 | Day 4 now closes at 285.00 |
| 13 | Day 6 | Day 5 | FEE_REVERSAL | 25.00 | Day 5 now closes at 285.00 |
| 14 | Day 6 | Day 6 | INTEREST | 0.79 | 0.10 + 0.10 + 0.26 + 0.11 + 0.11 + 0.11 |

Closing balances once everything is known: 250.00, 250.00, 650.00, 285.00, 285.00, 285.79. Auth-A and Auth-B live in the holds book, not here: Auth-A settled for 185.00 of 200.00, Auth-B declined.

## Layout

```
src/main/kotlin/ledger/
  domain/money       Money, Currency, and their errors           the shared value types
  domain/journal     Ledger, Entry, EntryType, Direction, Account, AccountId, Reason, ReversalOutcome
  domain/holds       Holds, Hold, HoldState, Placement, SettlementOutcome, UnmatchedReason
  domain/fees        FeeSweep, FeeSchedule, Assessment, SweepResult
  domain/interest    Interest, Capitalization
  application/       Replay (the clock), Window, Scenario (the accounts and E1 to E10)
    operation/       Operation and its five kinds
    journal/ holds/ close/   one use case per operation, plus the day close
    report/          DayReporter, DayReport, AccountReport, Note, ReportPrinter
src/test/kotlin/ledger/   one test class per type; KnownFailingTest is tagged known-failing
  stress/                 seeded random streams over 6, 30 and 90 days checking the invariants, plus adversarial cases
```

## The failing test

`KnownFailingTest` replays the six days, then on Day 8 reverses the Day 3 credit. The fee sweep copes. Interest does not: 0.79 was paid on Day 6 on a view that no longer holds, and the design has the entry type for the correction (`INTEREST_REVERSAL`) but no rule that books it. The annotation in the test says what that reveals.

## The criteria

Accepted: 1, 3, 5. Refused: 2, 4, 6 as worded, 7, 8. Reasons in [REJECTED.md](REJECTED.md).
