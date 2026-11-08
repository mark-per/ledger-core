# REJECTED

## Acceptance criteria refused

### 2. "E7 causes exactly one overdraft fee to be assessed, on Day 2"

Refused. After E7 the value-dated closings are Day 2 −370.00, Day 3 +5.00 (−370 − 25 + 400), Day 4 −360.00 before its fee, Day 5 −385.00 before its fee. The rule is "once per day per account when that day's closing is negative", so Days 2, 4 and 5 each earn a fee: three, all booked on Day 5, valued Days 2, 4 and 5. The replay prints them with the closing each day had before its fee. `ReplayTest.dayFiveChargesThreeFeesDeclinesAuthBAndRestatesThePast`.

The only ledger under which "exactly one" holds is one that assesses today and never re-reads the past. That ledger also makes criterion 1 pointless, because a Day 2 closing of −370.00 that nothing acts on is not a closing balance.

### 4. "Any settlement referencing an authorization ID not present in the ledger must be rejected and the funds must not leave the account"

Refused. In card processing a clearing is a command: the merchant's bank presents it and the issuer pays, with or without a matching authorization (offline terminals, lost authorization records, force posts). The customer's protection is a dispute, not a ledger that pretends the clearing never arrived. This core books E6 as `UNMATCHED_SETTLEMENT` with the reason "Auth-Z was never authorized", so the money leaves, the day's report flags it, and a dispute has something to point at. Day 4 closes at 285.00 rather than 465.00. The same rule covers a settlement against a declined, already settled or expired hold, each with its own reason.

### 6. "After E9, all balances and fees return to their pre-E7 values"

Refused as worded, accepted in effect. The ledger is append-only: E9 does not remove E7, it adds +620.00 valued Day 2. The three fee entries stay in the journal; the sweep books three `FEE_REVERSAL` entries at the same value days, so the net fee per day is zero and every closing balance equals its pre-E7 value. Balances return. Fees do not return; they are cancelled by new entries that remain visible. The word "return" implies mutation, and the brief forbids mutation. `ReplayTest.daySixReversesTheFeesRestoresTheBalancesAndLeavesAuthBDeclined`.

### 7. "The three BHD instalments in E10 must each be BHD 3.334"

Refused. 3 × 3.334 = 10.002. The account would be credited two fils nobody paid. Three equal three-decimal amounts summing to 10.000 do not exist; the instalments are 3.334, 3.333, 3.333 and sum exactly to 10.000. `MoneyTest.splitsTenDinarsIntoThreePartsThatSumExactly`.

### 8. "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded"

Refused. It contradicts the non-negotiable rule three lines above it ("must sum exactly"). The capitalized total is defined as the sum of the rounded dailies, so there is never a remainder to discard. `InterestTest.paysTheSumOfTheRoundedDailiesAndReportsTheExactFigure`.

## Accepted, with a note

- **1.** Day 2 closes at −370.00 at the end of Day 5 before fees. Asserted directly on the ledger (`ReplayTest.dayTwoClosesAtMinus370AtTheEndOfDayFiveBeforeAnyFee`) and printed on the Day 2 fee line.
- **3.** Auth-A settles for 185.00 against 200.00; the 15.00 residual is released.
- **5.** A hold reduces available balance, not ledger balance. True by construction, and tested on Auth-A on Day 2 (ledger 250.00, available 50.00). The premise never happens: Auth-B is declined, because available is −335.00 when it arrives.

## Approaches abandoned mid-build

### Fee sweep over today only

The first version of `FeeSweep` assessed only the day being closed. Its tests were green. Then the back-dated test went red: E7 produced one fee, on Day 5, instead of fees on Days 2, 4 and 5. The sweep was rewritten to run from the earliest value date touched since the last close through today, oldest first, and because it now re-reads past days it also had to learn to reverse a fee whose day is no longer negative. That second rule was not in the original design; it fell out of fixing the first.

### Interest rounded once, dailies nudged to match

Built, tested, and removed. The idea: sum the exact accruals, round once (0.802 → 0.80), then hand the one-fils difference to the day that lost most in rounding, so the dailies still sum to the payment. It pays the figure closest to the true interest and the leak from daily rounding grows with the period and the number of accounts, which is a real argument in production. It was removed after review because the printed Day 4 figure became 0.12 on a balance whose own rounding is 0.11, and a daily figure that is not its own rounding is harder to audit than a bounded, visible leak. The exact figure is printed beside the payment instead. The production argument is in the architecture document.

### Interest credit valued the day after capitalization

Built, committed and tested first: the Day 6 payment carried value date Day 7, so that interest earned on a day could not feed that day's own balance and "0.04 % of the closing balance" stayed literally true for every day. Day 6 closed at 285.00. Removed because the brief says the credit capitalizes "at end of Day 6", and pushing it to Day 7 put it outside the window the brief defines. The replacement is the soft close: fees are decided and posted, each day's accrual is computed on the soft-closed balance (fees in, that day's interest out), the interest is posted valued the same day, then the day closes. That removes the circularity without moving the credit. Day 6 closes at 285.79.

### Throwing on a reused authorization id

The first `Holds.place` threw a domain error when an auth id was seen twice, treating it as impossible input. It is not impossible; it is a network retry. Replaced by an idempotent placement that returns the original decision and places nothing, with a warning in the report.

### Sorting the stream by day label

Considered before any code, to make E10 land on Day 5. Dropped: "replayed in this order" is an instruction, and a core that reorders its input hides exactly the late arrival a ledger has to cope with.

### Fees final once assessed

Considered as the simpler rule for criterion 6. Dropped: the fee rule is a function of a day's closing, and a ledger that applies it on the way down but not on the way up is asymmetric in the bank's favour.

### Request and response models per use case

Considered to match a house convention. Dropped: the operation already is the request and the notes already are the response; thirteen wrapper classes would express nothing new. Each use case is one operation's orchestration, so `Replay` owns only the clock.
