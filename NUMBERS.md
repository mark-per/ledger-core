# NUMBERS

Every constant in the code: who set it, and for the ones I set, why that value and not half of it.

## Given by the brief

| Constant | Value | Where in the code |
|---|---|---|
| Overdraft fee | AED 25.00 per account per overdrawn day | `Scenario.FEES` |
| Daily interest rate | 0.04 % = 0.0004 | `Scenario.DAILY_INTEREST_RATE` |
| AED scale | 2 decimals | `Currency.AED` |
| BHD scale | 3 decimals | `Currency.BHD` |
| Window | six days | `Scenario.WINDOW` |
| Instalments for E10 | 3 | `Scenario.OPERATIONS` |
| Opening balances | 0.00 and 0.000 | an empty journal |

These are inputs. The scales are verified against ISO 4217 by `CurrencyTest`, so a wrong number there fails the build.

## Chosen here

### Overdraft limit: 0 per account (`Account.overdraftLimit`)

The approval rule in the brief is "available stays at or above zero", which is the special case of a limit of zero. The limit is a field on the account so the rule has one place to live. Half of zero is zero, so the question becomes "why not a positive limit": because the brief gives none, and a positive limit would approve Auth-B on Day 5 against money the account does not have, changing a number the brief fixed.

### Hold expiry: 7 days after placement (`Scenario.HOLD_EXPIRY_DAYS`)

Card schemes release an unsettled authorization after about seven days for most merchant types and up to thirty for hotels and car rental. Seven is the common default. Half, three or four days, would be shorter than the typical clearing cycle of two to three days plus weekend, so legitimate settlements would start landing on expired holds. No hold in this stream reaches seven days, so the number changes nothing here; it exists so that the state and the rule exist. Mapping expiry to merchant category codes is out of scope.

### Interest value date: the day after it is paid (`Interest.VALUE_DATE_LAG_DAYS = 1`)

Interest earned on a day must not feed that day's own balance, or "0.04 % of the closing balance" would be circular on the last day. One day is the smallest lag that keeps the rule literal. Half a day does not exist in a day-granular ledger. The consequence is that Day 6 closes at 285.00 and the 0.79 is in the account from Day 7; the alternative reading (valued Day 6, closing 285.79) is in AMBIGUITIES.

### Rounding mode: HALF_EVEN (`Money.ROUNDING`)

Not a magnitude, but the most consequential constant in the file: every interest accrual lands on the currency scale through it. HALF_UP rounds every exact half toward the customer; across accounts and days that is a systematic transfer. HALF_EVEN sends ties to the even neighbour and has no direction. In this stream no accrual lands on an exact half, so the choice changes no number, which is the honest thing to say about it.

### Instalment split: largest remainder, extra units to the earliest parts (`Money.allocate`)

10.000 BHD cannot be split into three equal three-decimal amounts. The parts must sum exactly to what was paid in, so the leftover fils goes somewhere. Earliest-first is deterministic and auditable: 3.334, 3.333, 3.333. Giving the leftover to the last part is equally exact but hides the rounding where a reader looks last.

### Settlement above its hold: no cap, book and flag (`SettlementOutcome.OverHold`)

A settlement is a command, not a request: the merchant has cleared and the issuer owes the money. The excess is recorded as a warning for a dispute. Schemes allow 15 to 20 % over-authorization for tips and fuel; I set no cap because a cap means refusing a clearing the network has already made, and the brief gives no rule for that. The stream never exercises it.

### Fee currency table: AED only (`Scenario.FEES`)

The brief gives a fee in AED and nothing for BHD. Inventing a BHD figure would be a number nobody set. An overdraft in a currency without a fee is reported as an error and not charged. ACC-002 never goes negative, so it never fires; `FeeSweepTest` covers it.

### Anchor date: Monday 2026-01-05 (`Scenario.WINDOW`)

The brief has no calendar; the ledger uses real dates. Any anchor works and this one is arbitrary. It is a Monday so that Day 6 falls on a Saturday, which makes the point that nothing in these rules reads a calendar: value dates arrive set, interest accrues every calendar day, and every day closes. A business-day calendar belongs upstream, where value dates are assigned.
