# AMBIGUITIES

What the brief left open, in the order I would want to be asked about it, each with the resolution and where it is enforced. A test is named where one exists.

## 1. Which day the interest credit is valued

The brief says accruals capitalize "as a single credit at end of Day 6" and defines a day's closing as every entry valued on or before it. Read literally together, the credit is in Day 6's closing and Day 6's accrual is computed on a balance that includes it, which is circular. Two consistent readings:

- Value the credit on Day 6 and compute Day 6's accrual on the balance before it. Day 6 closes at 285.79.
- Value the credit on Day 7. Interest earned on a day never feeds that day's balance; "0.04 % of the closing balance" stays literally true for every day. Day 6 closes at **285.00** and the 0.79 is in the account from Day 7.

**Resolved: the second.** The Day 6 report says so on its interest line: "paid 0.79 booked today, value Day 7, not in today's closing". A reviewer expecting 285.79 should read it as 285.00 plus 0.79 pending, not as missing interest. `ReplayTest.daySixPaysTheSumOfTheRoundedDailiesValuedTheNextDay`.

## 2. Settlement with no authorization (E6)

The brief defines SETTLEMENT as an authorization settling. Auth-Z never authorized anything. Criterion 4 says reject and keep the funds. **Resolved: booked as `UNMATCHED_SETTLEMENT`, flagged with the reason.** A settlement is a command, not a request: the merchant has cleared and the issuer's obligation exists; the customer's remedy is a dispute, which is raised from the reason recorded on the entry. Day 4 closes at 285.00, not 465.00, and every later number follows. Criterion 4 is refused in REJECTED.md. `ReplayTest.anUnmatchedSettlementIsBookedAndFlagged`.

## 3. Does a back-dated entry re-open past days for fees

E7 posts on Day 5 valued Day 2. A ledger that assesses only today charges one fee. The brief's definition of closing is value-dated, and criterion 1 says Day 2 closes at −370.00 when evaluated on Day 5. **Resolved: yes.** When a day closes, the sweep starts at the earliest value date touched since the last close and runs oldest first through today. Days 2, 4 and 5 each earn a fee. `FeeSweepBackDatingTest.aBackDatedDebitChargesEveryPastDayItPushesNegative`.

## 4. Does a day's own fee count when deciding whether that day is overdrawn

The brief's closing definition ("all entries with value_date ≤ that day") includes the day's own fee; criterion 1 says "before any fee is assessed". Including it lets a fee keep itself alive after a day is cured: +10.00 before the fee reads −15.00 with it, so the fee would stand. **Resolved: each day is judged on its closing excluding its own fee and fee reversal.** Earlier days' fees do count, since that money is gone (Day 3 is 5.00, not 30.00). The report prints that figure on each fee line. `LedgerTest.closingBeforeOwnFeeIgnoresOnlyThatDaysFeeEntries`.

## 5. What happens to a fee when the day stops being negative

E9 restores Day 2 to 250.00. The fees cannot be deleted. **Resolved: a `FEE_REVERSAL` mirroring each fee entry, at the same value day, linked to it.** This fires whatever cured the day, a reversed debit or a back-dated credit alike, because the fee rule is a function of the day's closing in both directions. A bank that keeps the fee when a late deposit cures the overdraft would change this one rule. `FeeSweepBackDatingTest.reversingTheCauseReversesTheFeesAtTheSameValueDates`, `aBackDatedCreditCuresAnOverdraftAndReversesItsFee`.

## 6. "The rounded daily accruals must sum exactly to the capitalized total"

Two readings: the total is the sum of the rounded dailies, or the total is the exact interest rounded once with the dailies adjusted to match. **Resolved: the sum of the rounded dailies, 0.79.** Every printed daily is its own rounding and the identity holds by construction. The round-once alternative (0.80, with Day 4 shown as 0.12 instead of its own 0.11) was built and removed; see REJECTED.md. The exact figure, 0.802, is printed beside the payment so the rounding leak is visible. `InterestTest.paysTheSumOfTheRoundedDailiesAndReportsTheExactFigure`.

## 7. Interest on days that get restated

Day 2 accrues 0.10 on Day 2, 0.00 on Day 5's view, 0.10 again on Day 6's view. **Resolved: accruals are recomputed at each close for every day the close touched, and nothing is booked before the last day**, so no adjustment is ever needed inside the window. Provisional figures are printed daily. After capitalization a restatement would need an `INTEREST_REVERSAL` and a new `INTEREST` entry; that rule is outside the window and is the subject of the one failing test. `InterestTest.aBackDatedEntryChangesEarlierDaysAccrualsWhenRecomputed`.

## 8. Event order versus day labels (E10)

E10 is labelled Day 5 but follows E9, a Day 6 event. "Replayed in this order" is an instruction. **Resolved: stream order is law and the clock never goes backwards.** E10 is booked on Day 6 with its stated value date, Day 5, and the report carries a warning. Interest is identical either way because interest reads value dates. `ReplayTest.e10ArrivesLateIsSplitExactlyAndEarnsFromItsValueDate`.

## 9. Booking date versus the event's day label

An operation's "Day N" is the sender's label; the booking date is the processing day on which the ledger received it, and it can never be earlier than the clock. For nine operations they agree; for E10 they do not, and the booking date wins.

## 10. Three "equal" instalments of 10.000 BHD

Three equal three-decimal amounts summing to 10.000 do not exist. **Resolved: largest remainder, 3.334 / 3.333 / 3.333**, the odd fils to the first instalment. Criterion 7 (each 3.334, total 10.002) is refused. `MoneyTest.splitsTenDinarsIntoThreePartsThatSumExactly`.

## 11. Rounding ties

Not specified. **Resolved: HALF_EVEN** at the currency scale, the unbiased tie rule. No figure in this stream lands on a tie, so it changes no number. `MoneyTest.roundsAnExactHalfToTheEvenNeighbour`.

## 12. Is Auth-B approved

Criterion 5 says "if Auth-B is approved". At E8 the ledger as known is −335.00 (E7 has landed) and no holds are active, so available is −335.00 and a 90.00 hold is **declined**. The criterion's rule is right; its premise is false. "Auth-B is never settled inside the window" is therefore moot. `ReplayTest.dayFiveChargesThreeFeesDeclinesAuthBAndRestatesThePast`.

## 13. Which balance an authorization reads, and whether the decision can change

**Resolved: the closing balance as of today, minus active holds, at the moment the authorization arrives; the decision is final.** E9 later makes the account positive again and does not re-open Auth-B. `ReplayTest.daySixReversesTheFeesRestoresTheBalancesAndLeavesAuthBDeclined`.

## 14. What the authorization id is, and a repeated one

Real card traffic carries two identifiers. The network's reference (retrieval reference or system trace number) arrives with the request and identifies the message; the approval code is generated by the issuer when it says yes and is quoted back by the clearing as proof. The brief gives `Auth-A` inside the authorization request itself, before any decision, so it is the first kind: an external reference and a matching key. **Resolved: the auth id is an input; the model issues no approval code.** The production addition is one field on `Hold`, an approval code generated on approval, and a settlement that must quote both: the reference to find the hold, the code to prove the approval. Neither changes any rule here.

Networks retry an authorization when a response times out, so the same reference can arrive twice. **Resolved: placing a hold is idempotent on (account, auth id).** The second arrival returns the original decision, places nothing, and the report notes the repeat. The same id on another account is a separate hold; a settlement quoting another account's id finds nothing. Not in the stream. `HoldsTest.aRepeatedAuthorizationIdReturnsTheOriginalDecisionAndPlacesNothing`, `HoldsTest.theSameAuthorizationIdOnAnotherAccountIsANewHold`.

## 15. Settlement above its hold

Not in the stream. **Resolved: booked and flagged with the excess, hold consumed in full**, same reasoning as item 2. `HoldsTest.settlingAboveTheHoldIsBookedAndFlaggedWithTheExcess`.

## 16. Settlement against a declined, already settled, or expired hold

Not in the stream. **Resolved: booked as `UNMATCHED_SETTLEMENT` with the reason naming which; the hold is left unchanged.** `HoldsTest`, the three `...IsUnmatched...` tests.

## 17. Settlement below its hold

E5 settles 185.00 against 200.00. **Resolved: the residual 15.00 is released; the hold is SETTLED.** Multiple clearings against one hold are not modelled. `HoldsTest.settlingBelowTheHoldReleasesTheRest`.

## 18. Hold expiry

Not specified. **Resolved: seven days after placement, at that day's close; the hold is released and later settlements against it are unmatched.** Nothing in the stream reaches seven days. `ReplayTest.anUnsettledHoldExpiresAtTheCloseOfTheSeventhDayAfterPlacement`.

## 19. Value date on an authorization

The brief gives E3 and E8 a value date. A hold moves no money, so a value date has nothing to attach to. **Resolved: dropped from the model.**

## 20. Why E7 is reversed

E9 says only "reverses E7". A reversal is a correction, not a refund: it takes the original's value date so the history reads as if the original never happened. **Resolved: a reversal must carry a `Reason`; the scenario passes "not stated by the brief".** A refund would be a CREDIT with its own value date.

## 21. What can be reversed

**Resolved: entries that came from an operation, looked up by that operation's id, all of them (an instalment credit reverses as three entries).** Fees and interest are corrected by their own rules. A reversal of a reversal, a second reversal of the same operation, and a reversal quoting another account's operation are refused with a note. `LedgerTest.aReversalIsRefusedWhenNothingWasBookedTwiceOrAcrossAccountsOrOfAReversal`.

## 22. Fee for a BHD account

The brief gives the fee in AED only. **Resolved: the fee is defined per currency; an overdraft in a currency without one is reported as an error and not charged.** Never fires in the stream. `FeeSweepTest.anOverdraftInACurrencyWithoutAFeeIsReportedNotCharged`.

## 23. Calendar days versus processing days

The brief has no calendar. **Resolved: every calendar day is a processing day and accrues; value dates arrive set and the ledger never computes one, so no business-day calendar appears in any rule.** The ledger stores real dates; "Day N" is a label from an anchor. Day 6 falls on a Saturday under the chosen anchor and nothing notices.

## 24. When a day closes

Not specified. **Resolved: the ledger never reads a clock.** A day ends when the replay closes it, which it does when the labels move to the next day and once more at the end of the stream. In production the same close is triggered at the bank's cut-off by whatever owns the business date. Anything arriving after a close is booked on a later day with its own value date and handled as a back-dated entry.

## 25. Are rejected and declined operations part of the ledger

They moved no money, so they are not entries. **Resolved: recorded as notes in the day's report**, so the output is a complete record of what was received, not only of what posted.

## 26. Concurrency

Not specified. **Resolved: single-threaded, no locks.** One account's journal is written by one writer at a time; the core assumes that and does not enforce it.

## 27. A repeated operation id

A stream can deliver the same operation twice. Authorizations are idempotent on their auth id (item 14); the other operations have no such key except their own id. **Resolved: every operation is idempotent on its id.** A second delivery is ignored with a warning, whatever its kind, so E7 twice books one debit and a later reversal of E7 undoes exactly one entry. `ReplayTest.aRepeatedOperationIdIsIgnoredWithAWarning`.

## 28. Operations after the window

The brief ends on Day 6. An operation labelled Day 7 or later is not in the stream, but a ledger must say what it would do. **Resolved: days after the window close normally and are reported; fees apply; accruals for those days belong to a next period that this run never pays.** This is what lets the failing test exist: a reversal arriving after capitalization is exactly an after-window operation. Refusing such operations would have been the other defensible choice. `AdversarialStressTest.anOperationAfterTheWindowIsClosedAndReportedAtTheEndOfTheStream`.

## 29. Value dates before Day 1

Opening balances are zero, so an entry valued before Day 1 contradicts the brief. **Resolved: accepted into balances from Day 1 on, earns nothing before Day 1, and the report carries a warning.** `AdversarialStressTest.aValueDateBeforeTheWindowAccruesNoInterestInsideThePeriod`.

## 30. A credit too small for its instalment count

0.02 AED cannot be paid in three instalments without a zero part, and a zero entry means nothing moved. **Resolved: refused before anything is booked**, and in general an operation that books several entries is all or nothing. `AdversarialStressTest.aCreditSmallerThanItsInstalmentCountIsRefusedBeforeAnythingIsBooked`.

## 31. A day cured and then broken again

A back-dated credit cures Day 2 (fee reversed); a later back-dated debit breaks it again. **Resolved: charged again.** The fee rule is a function of the day's closing, so the trail reads fee, reversal, fee, and at most one fee stands at any time. "One fee per day" in the design means one standing fee, and the wording was tightened after a stress test found the ambiguity. `AdversarialStressTest.aDayCuredThenReBrokenOnALaterDayIsChargedAgainButNeverCarriesTwoStandingFees`.
