package ledger.application

import ledger.application.Scenario.ACC_001
import ledger.application.Scenario.ACC_002
import ledger.application.Scenario.OPERATIONS
import ledger.application.Scenario.WINDOW
import ledger.application.Scenario.replay
import ledger.application.operation.Operation.Authorization
import ledger.application.operation.Operation.Credit
import ledger.application.operation.Operation.Debit
import ledger.application.operation.Operation.Reversal
import ledger.application.report.AccountReport
import ledger.application.report.DayReport
import ledger.application.report.Note
import ledger.application.report.Restatement
import ledger.application.report.Severity.ERROR
import ledger.application.report.Severity.WARNING
import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.holds.HoldState.EXPIRED
import ledger.domain.holds.HoldState.SETTLED
import ledger.domain.journal.EntryType.CREDIT
import ledger.domain.journal.EntryType.DEBIT
import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.EntryType.REVERSAL
import ledger.domain.journal.EntryType.SETTLEMENT
import ledger.domain.journal.EntryType.UNMATCHED_SETTLEMENT
import ledger.domain.journal.Reason
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayTest {
    private val acc1 = ACC_001.id
    private val acc2 = ACC_002.id

    private fun aed(text: String) = Money.of(text, AED)

    private fun bhd(text: String) = Money.of(text, BHD)

    private fun date(day: Int): LocalDate = WINDOW.date(day)

    private fun acc1On(day: Int): AccountReport = REPORTS[day - 1].account(acc1)

    private fun acc2On(day: Int): AccountReport = REPORTS[day - 1].account(acc2)

    private fun replayUntil(lastEventId: String): Replay {
        val replay = replay()
        OPERATIONS.takeWhile { it.id != lastEventId }.forEach(replay::process)
        return replay
    }

    @Test
    fun closesSixDaysFromTheAnchorDate() {
        assertEquals((1..6).toList(), REPORTS.map { it.day })
        assertEquals(LocalDate.of(2026, 1, 5), REPORTS.first().date)
        assertEquals(LocalDate.of(2026, 1, 10), REPORTS.last().date)
    }

    @Test
    fun daysOneToFourBeforeAnythingIsBackDated() {
        assertEquals(aed("250.00"), acc1On(1).closing)
        assertEquals(aed("250.00"), acc1On(2).closing)
        assertEquals(aed("50.00"), acc1On(2).available)
        assertEquals(ACTIVE, acc1On(2).holds.single().state)
        assertEquals(aed("650.00"), acc1On(3).closing)
        assertEquals(aed("285.00"), acc1On(4).closing)
        assertEquals(SETTLED, acc1On(4).holds.single().state)
        assertTrue(acc1On(4).fees.isEmpty())
    }

    @Test
    fun anUnmatchedSettlementIsBookedAndFlagged() {
        val e6 = acc1On(4).posted.single { it.source == "E6" }

        assertEquals(UNMATCHED_SETTLEMENT, e6.type)
        assertEquals(aed("-180.00"), e6.amount)
        assertEquals(
            Note(WARNING, "E6 booked as unmatched settlement; Auth-Z was never authorized"),
            acc1On(4).notes.single(),
        )
    }

    @Test
    fun dayTwoClosesAtMinus370AtTheEndOfDayFiveBeforeAnyFee() {
        val replay = replayUntil("E9")

        assertEquals(5, replay.day)
        assertEquals(aed("-370.00"), replay.ledger.closingBeforeOwnFee(acc1, date(2)))
    }

    @Test
    fun dayFiveChargesThreeFeesDeclinesAuthBAndRestatesThePast() {
        val day5 = acc1On(5)

        assertEquals(listOf(date(2), date(4), date(5)), day5.fees.map { it.valueDate })
        assertTrue(day5.fees.all { it.type == OVERDRAFT_FEE && it.bookingDate == date(5) })
        assertEquals(aed("-370.00"), day5.closingsBeforeFee.getValue(date(2)))
        assertEquals(aed("-410.00"), day5.closing)
        assertEquals(aed("-410.00"), day5.available)
        assertEquals(DECLINED, day5.holds.single { it.authId == "Auth-B" }.state)
        assertEquals(
            listOf(
                Restatement(date(2), aed("250.00"), aed("-395.00")),
                Restatement(date(3), aed("650.00"), aed("5.00")),
                Restatement(date(4), aed("285.00"), aed("-385.00")),
            ),
            day5.restated,
        )
        assertEquals(ERROR, day5.notes.single().severity)
        assertTrue(day5.notes.single().message.startsWith("E8 declined"))
    }

    @Test
    fun daySixReversesTheFeesRestoresTheBalancesAndLeavesAuthBDeclined() {
        val day6 = acc1On(6)
        val ledger = replay().also { it.run(OPERATIONS) }.ledger

        assertEquals(List(3) { FEE_REVERSAL }, day6.fees.map { it.type })
        assertEquals(listOf(date(2), date(4), date(5)), day6.fees.map { it.valueDate })
        assertEquals(
            listOf("250.00", "250.00", "650.00", "285.00", "285.00", "285.00"),
            (1..6).map { ledger.closing(acc1, date(it)).toString() },
        )
        assertTrue((1..6).all { ledger.standingFee(acc1, date(it)) == null })
        assertEquals(aed("285.00"), day6.closing)
        assertEquals(DECLINED, day6.holds.single { it.authId == "Auth-B" }.state)
        val e9 = day6.posted.single()
        assertEquals("E9", e9.source)
        assertEquals("reverses E7, not stated by the brief", e9.detail)
        assertEquals(date(2), e9.valueDate)
    }

    @Test
    fun daySixPaysTheSumOfTheRoundedDailiesValuedTheNextDay() {
        val day6 = acc1On(6)
        val capitalization = day6.capitalization!!

        assertEquals(BigDecimal("0.802"), capitalization.exact.stripTrailingZeros())
        assertEquals(aed("0.79"), capitalization.paid)
        assertEquals(listOf("0.10", "0.10", "0.26", "0.11", "0.11", "0.11"), day6.accruals.values.map { it.toString() })
        assertEquals(date(6), capitalization.entry!!.bookingDate)
        assertEquals(date(7), capitalization.entry!!.valueDate)
    }

    @Test
    fun theJournalHoldsEveryKindOfEntryTheStreamProduces() {
        val types = replay().also { it.run(OPERATIONS) }.ledger.entries(acc1).map { it.type }.toSet()

        assertEquals(
            setOf(
                CREDIT,
                DEBIT,
                SETTLEMENT,
                UNMATCHED_SETTLEMENT,
                REVERSAL,
                OVERDRAFT_FEE,
                FEE_REVERSAL,
                INTEREST,
            ),
            types,
        )
    }

    @Test
    fun e10ArrivesLateIsSplitExactlyAndEarnsFromItsValueDate() {
        assertEquals(bhd("0.000"), acc2On(5).closing)
        val day6 = acc2On(6)

        assertEquals(listOf("3.334", "3.333", "3.333"), day6.posted.map { it.amount.toString() })
        assertTrue(day6.posted.all { it.source == "E10" && it.bookingDate == date(6) && it.valueDate == date(5) })
        assertEquals(listOf(Restatement(date(5), bhd("0.000"), bhd("10.000"))), day6.restated)
        assertEquals(WARNING, day6.notes.single().severity)
        assertTrue(day6.notes.single().message.startsWith("E10 is labelled Day 5 but arrived on Day 6"))
        assertEquals(bhd("0.008"), day6.capitalization!!.paid)
        assertEquals(bhd("10.000"), day6.closing)
    }

    @Test
    fun aSettlementCanBeReversedLaterByQuotingItsEventAndTakesItsValueDate() {
        val replay = replayUntil("E10")

        replay.process(Reversal("E12", 6, acc1, reverses = "E6", reason = Reason("dispute won, never authorized")))

        val reversal = replay.ledger.fromSource("E12").single()
        assertEquals(REVERSAL, reversal.type)
        assertEquals(aed("180.00"), reversal.amount)
        assertEquals(date(4), reversal.valueDate)
    }

    @Test
    fun aReversalOfSomethingNeverBookedIsRejected() {
        val replay = replayUntil("E10")

        replay.process(Reversal("E13", 6, acc1, reverses = "E8", reason = Reason("typo")))

        assertEquals(
            Note(ERROR, "E13 rejected; nothing is booked from E8"),
            replay.closeDay().account(acc1).notes.single(),
        )
    }

    @Test
    fun aSecondReversalOfTheSameEventIsRejected() {
        val replay = replayUntil("E10")

        replay.process(Reversal("E14", 6, acc1, reverses = "E7", reason = Reason("again")))

        assertEquals(
            Note(ERROR, "E14 rejected; E7 was already reversed"),
            replay.closeDay().account(acc1).notes.single(),
        )
    }

    @Test
    fun aReversalAgainstAnotherAccountsEventIsRejected() {
        val replay = replayUntil("E10")

        replay.process(Reversal("E15", 6, acc2, reverses = "E7", reason = Reason("wrong account")))

        assertEquals(
            Note(ERROR, "E15 rejected; E7 belongs to another account"),
            replay.closeDay().account(acc2).notes.single(),
        )
    }

    @Test
    fun aRepeatedOperationIdIsIgnoredWithAWarning() {
        val replay = replayUntil("E8")

        replay.process(Debit("E7", 5, acc1, aed("620.00"), valueDay = 2))
        val day5 = replay.closeDay().account(acc1)

        assertEquals(1, replay.ledger.fromSource("E7").size)
        assertTrue(
            day5.notes.any { it == Note.warning("E7 was already processed; this delivery is ignored") },
        )
    }

    @Test
    fun anUnsettledHoldExpiresAtTheCloseOfTheSeventhDayAfterPlacement() {
        val replay = replay()
        replay.process(Credit("X1", 1, acc1, aed("500.00"), valueDay = 1))
        replay.process(Authorization("X2", 1, acc1, "Auth-X", aed("100.00")))
        repeat(7) { replay.closeDay() }

        val day8 = replay.closeDay().account(acc1)

        assertEquals(EXPIRED, day8.holds.single().state)
        assertEquals(aed("501.20"), day8.available)
        assertEquals(Note(WARNING, "Auth-X expired unsettled, hold of 100.00 released"), day8.notes.single())
    }

    private companion object {
        val REPORTS: List<DayReport> by lazy { replay().run(OPERATIONS) }
    }
}
