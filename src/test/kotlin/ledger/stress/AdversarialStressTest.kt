package ledger.stress

import ledger.application.Replay
import ledger.application.Window
import ledger.application.operation.Operation
import ledger.application.operation.Operation.Authorization
import ledger.application.operation.Operation.Credit
import ledger.application.operation.Operation.Debit
import ledger.application.operation.Operation.Reversal
import ledger.application.operation.Operation.Settlement
import ledger.application.report.Severity.ERROR
import ledger.application.report.Severity.WARNING
import ledger.domain.error.DomainError
import ledger.domain.fees.FeeSchedule
import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.holds.HoldState.EXPIRED
import ledger.domain.holds.HoldState.SETTLED
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
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
import org.junit.jupiter.api.Tag
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("stress")
class AdversarialStressTest {
    private val anchor = LocalDate.of(2026, 1, 5)
    private val rate = BigDecimal("0.0004")
    private val fees = FeeSchedule(mapOf(AED to Money.of("25.00", AED)))
    private val a = Account(AccountId("A"), AED)
    private val b = Account(AccountId("B"), AED)
    private val h = Account(AccountId("H"), BHD)
    private val reason = Reason("stress")

    private fun aed(text: String) = Money.of(text, AED)

    private fun bhd(text: String) = Money.of(text, BHD)

    private fun replay(days: Int = 12): Replay = Replay(listOf(a, b, h), Window(anchor, days), fees, rate, 7)

    private fun day(n: Int): LocalDate = anchor.plusDays(n - 1L)

    private fun Replay.feesOn(
        account: Account,
        valueDay: Int,
    ) = ledger.entries(account.id).filter { it.valueDate == day(valueDay) && it.type == OVERDRAFT_FEE }

    private fun Replay.feeTrail(account: Account) =
        ledger
            .entries(account.id)
            .filter { it.type == OVERDRAFT_FEE || it.type == FEE_REVERSAL }
            .map { "${it.type}@${it.valueDate}" }
            .sorted()

    private fun backDatedMix(order: List<Operation>): Replay {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        order.forEach(replay::process)
        replay.closeDay()
        return replay
    }

    @Test
    fun backDatedDebitsAndCreditsInOneDayGiveTheSameFeesInEitherOrder() {
        val ops =
            listOf(
                Debit("D2", 4, a.id, aed("150.00"), valueDay = 2),
                Credit("C3", 4, a.id, aed("80.00"), valueDay = 3),
                Debit("D1", 4, a.id, aed("10.00"), valueDay = 1),
                Credit("C2", 4, a.id, aed("50.00"), valueDay = 2),
            )
        val forward = backDatedMix(ops)
        val backward = backDatedMix(ops.reversed())
        assertEquals(forward.feeTrail(a), backward.feeTrail(a))
        assertEquals(
            (1..4).map { forward.ledger.closing(a.id, day(it)) },
            (1..4).map { backward.ledger.closing(a.id, day(it)) },
        )
        assertEquals(listOf("OVERDRAFT_FEE@${day(2)}"), forward.feeTrail(a))
    }

    @Test
    fun aCureAndReBreakInsideOneDayIsJudgedOnTheNetClosing() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Debit("D1", 2, a.id, aed("150.00"), valueDay = 2))
        replay.process(Credit("C2", 3, a.id, aed("100.00"), valueDay = 2))
        replay.process(Debit("D2", 3, a.id, aed("100.00"), valueDay = 2))
        replay.closeDay()
        assertEquals(listOf("OVERDRAFT_FEE@${day(2)}"), replay.feeTrail(a).filter { it.endsWith("${day(2)}") })
    }

    @Test
    fun aDayCuredThenReBrokenOnALaterDayIsChargedAgainButNeverCarriesTwoStandingFees() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Debit("D1", 2, a.id, aed("150.00"), valueDay = 2))
        replay.process(Credit("C2", 3, a.id, aed("100.00"), valueDay = 2))
        replay.process(Debit("D2", 4, a.id, aed("100.00"), valueDay = 2))
        replay.closeDay()
        val trail =
            replay.ledger.entries(a.id).filter {
                it.valueDate == day(2) && it.type in setOf(OVERDRAFT_FEE, FEE_REVERSAL)
            }
        assertEquals(
            listOf(OVERDRAFT_FEE, FEE_REVERSAL, OVERDRAFT_FEE),
            trail.map {
                it.type
            },
            "fee trail for Day 2: ${replay.feeTrail(a)}",
        )
        assertEquals(1, trail.count { it.type == OVERDRAFT_FEE } - trail.count { it.type == FEE_REVERSAL })
    }

    @Test
    fun reversalsOfReversalsTwiceAcrossAccountsAndUnknownIdsBookNothing() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Credit("CB", 1, b.id, aed("100.00"), valueDay = 1))
        replay.process(Reversal("R1", 2, a.id, reverses = "C1", reason = reason))
        val before = replay.ledger.entries(a.id).size
        replay.process(Reversal("R2", 2, a.id, reverses = "R1", reason = reason))
        replay.process(Reversal("R3", 2, a.id, reverses = "C1", reason = reason))
        replay.process(Reversal("R4", 2, a.id, reverses = "CB", reason = reason))
        replay.process(Reversal("R5", 2, a.id, reverses = "NOPE", reason = reason))
        replay.process(Reversal("R6", 2, a.id, reverses = "R5", reason = reason))
        val report = replay.closeDay().account(a.id)
        assertEquals(before, replay.ledger.entries(a.id).size)
        assertEquals(1, replay.ledger.entries(b.id).size)
        assertEquals(5, report.notes.count { it.severity == ERROR })
        assertEquals(REVERSAL, replay.ledger.fromSource("R1").single().type)
    }

    @Test
    fun settlementsAboveEqualAndBelowTheHoldAllBookAsSettlement() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("1000.00"), valueDay = 1))
        listOf("X" to "100.00", "Y" to "100.00", "W" to "100.00").forEach { (auth, amount) ->
            replay.process(Authorization("AU$auth", 1, a.id, auth, aed(amount)))
        }
        replay.process(Settlement("SX", 2, a.id, "X", aed("120.00"), valueDay = 2))
        replay.process(Settlement("SY", 2, a.id, "Y", aed("100.00"), valueDay = 2))
        replay.process(Settlement("SW", 2, a.id, "W", aed("60.00"), valueDay = 2))
        replay.process(Settlement("SW2", 2, a.id, "W", aed("40.00"), valueDay = 2))
        val report = replay.closeDay().account(a.id)
        assertEquals(listOf(SETTLEMENT, SETTLEMENT, SETTLEMENT), listOf("SX", "SY", "SW").map { replay.typeOf(it) })
        assertEquals(UNMATCHED_SETTLEMENT, replay.typeOf("SW2"))
        assertTrue(replay.holds.forAccount(a.id).all { it.state == SETTLED })
        assertEquals(aed("680.00"), report.closing)
        assertEquals(aed("680.00"), report.available)
        assertTrue(report.notes.any { it.severity == WARNING && it.message.contains("by 20.00") })
    }

    private fun Replay.typeOf(source: String) = ledger.fromSource(source).single().type

    @Test
    fun settlementsAgainstDeclinedSettledAndExpiredHoldsAreUnmatchedAndLeaveTheHold() {
        val replay = replay(12)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Authorization("AD", 1, a.id, "DEC", aed("100.01")))
        replay.process(Authorization("AE", 1, a.id, "EXP", aed("40.00")))
        replay.process(Authorization("AS", 1, a.id, "SET", aed("60.00")))
        replay.process(Settlement("S1", 2, a.id, "SET", aed("60.00"), valueDay = 2))
        replay.process(Settlement("S2", 2, a.id, "SET", aed("60.00"), valueDay = 2))
        replay.process(Settlement("S3", 2, a.id, "DEC", aed("10.00"), valueDay = 2))
        replay.process(Settlement("S4", 9, a.id, "EXP", aed("10.00"), valueDay = 9))
        assertEquals(
            listOf(SETTLEMENT, UNMATCHED_SETTLEMENT, UNMATCHED_SETTLEMENT),
            listOf("S1", "S2", "S3").map {
                replay.typeOf(it)
            },
        )
        assertEquals(UNMATCHED_SETTLEMENT, replay.typeOf("S4"))
        val states = replay.holds.forAccount(a.id).associate { it.authId to it.state }
        assertEquals(mapOf("DEC" to DECLINED, "EXP" to EXPIRED, "SET" to SETTLED), states)
        assertEquals(aed("60.00"), replay.holds.forAccount(a.id).single { it.authId == "SET" }.settledFor)
    }

    @Test
    fun aHoldSettledOnItsExpiryDayBeforeTheCloseStillMatches() {
        val replay = replay(12)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Authorization("A1", 1, a.id, "H1", aed("40.00")))
        replay.process(Authorization("A2", 1, a.id, "H2", aed("40.00")))
        replay.process(Settlement("S1", 8, a.id, "H1", aed("40.00"), valueDay = 8))
        val dayEight = replay.closeDay().account(a.id)
        assertEquals(SETTLEMENT, replay.typeOf("S1"))
        assertEquals(day(8), replay.holds.forAccount(a.id).single { it.authId == "H2" }.expiresOn)
        assertEquals(EXPIRED, replay.holds.forAccount(a.id).single { it.authId == "H2" }.state)
        assertEquals(aed("60.00"), dayEight.available)
    }

    @Test
    fun aSettlementOnAnotherAccountDoesNotConsumeThisAccountsHold() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Authorization("A1", 1, a.id, "SHARED", aed("50.00")))
        replay.process(Settlement("S1", 1, b.id, "SHARED", aed("50.00"), valueDay = 1))
        assertEquals(UNMATCHED_SETTLEMENT, replay.typeOf("S1"))
        assertEquals(ACTIVE, replay.holds.forAccount(a.id).single().state)
    }

    @Test
    fun aSettlementInAnotherCurrencyQuotingAForeignHoldIsATypedOutcomeNotACrash() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Authorization("A1", 1, a.id, "SHARED", aed("50.00")))
        val outcome = runCatching { replay.process(Settlement("S1", 1, h.id, "SHARED", bhd("5.000"), valueDay = 1)) }
        assertEquals(ACTIVE, replay.holds.forAccount(a.id).single().state, "hold after ${outcome.exceptionOrNull()}")
        assertEquals(UNMATCHED_SETTLEMENT, replay.typeOf("S1"))
    }

    @Test
    fun anAuthIdReusedOnAnotherAccountIsThatAccountsOwnHold() {
        val replay = replay(6)
        replay.process(Credit("CB", 1, b.id, aed("100.00"), valueDay = 1))
        replay.process(Authorization("A1", 1, a.id, "SHARED", aed("50.00")))
        replay.process(Authorization("A2", 1, b.id, "SHARED", aed("50.00")))
        assertEquals(DECLINED, replay.holds.forAccount(a.id).single().state)
        assertEquals(ACTIVE, replay.holds.forAccount(b.id).single().state)
    }

    @Test
    fun aZeroSettlementLeavesTheHoldUntouchedWhenItIsRefused() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Authorization("A1", 1, a.id, "H1", aed("50.00")))
        assertFailsWith<DomainError> {
            replay.process(Settlement("S1", 1, a.id, "H1", aed("0.00"), valueDay = 1))
        }
        assertEquals(ACTIVE, replay.holds.forAccount(a.id).single().state)
    }

    @Test
    fun aNegativeAuthorizationIsRefusedAsImpossibleInput() {
        val replay = replay(6)
        assertFailsWith<DomainError> {
            replay.process(Authorization("A1", 1, a.id, "NEG", aed("-100.00")))
        }
    }

    @Test
    fun overdraftLimitApprovesExactlyToTheLimitAndStillChargesTheFee() {
        val limited = Account(AccountId("L"), AED, aed("100.00"))
        val replay = Replay(listOf(limited), Window(anchor, 3), fees, rate, 7)
        replay.process(Authorization("A1", 1, limited.id, "AT", aed("100.00")))
        replay.process(Authorization("A2", 1, limited.id, "OVER", aed("0.01")))
        replay.process(Debit("D1", 1, limited.id, aed("50.00"), valueDay = 1))
        replay.closeDay()
        assertEquals(listOf(ACTIVE, DECLINED), replay.holds.forAccount(limited.id).map { it.state })
        assertEquals(1, replay.ledger.entries(limited.id).count { it.type == OVERDRAFT_FEE })
    }

    @Test
    fun aBhdOverdraftIsReportedAsAnErrorAndNotCharged() {
        val replay = replay(3)
        replay.process(Debit("D1", 1, h.id, bhd("1.001"), valueDay = 1))
        val report = replay.closeDay().account(h.id)
        assertTrue(replay.ledger.entries(h.id).none { it.type == OVERDRAFT_FEE })
        assertTrue(report.notes.any { it.severity == ERROR && it.message.contains("no overdraft fee is defined") })
        assertEquals(bhd("-1.001"), report.closing)
    }

    @Test
    fun accrualsOnHalfEvenTiesRoundToTheEvenNeighbour() {
        val replay = replay(2)
        replay.process(Credit("C1", 1, a.id, aed("12.50"), valueDay = 1))
        replay.process(Credit("H1", 1, h.id, bhd("1.250"), valueDay = 1))
        replay.process(Credit("C2", 2, a.id, aed("25.00"), valueDay = 2))
        replay.process(Credit("H2", 2, h.id, bhd("2.500"), valueDay = 2))
        val last = replay.closeDay()
        assertEquals(listOf(aed("0.00"), aed("0.02")), last.account(a.id).capitalization!!.dailies.values.toList())
        assertEquals(aed("0.02"), last.account(a.id).capitalization!!.paid)
        assertEquals(listOf(bhd("0.000"), bhd("0.002")), last.account(h.id).capitalization!!.dailies.values.toList())
        assertEquals(Money.round(BigDecimal("0.0025"), AED), aed("0.00"))
        assertEquals(Money.round(BigDecimal("0.0135"), AED), aed("0.01"))
    }

    @Test
    fun anInstalmentSplitSumsAndFrontLoadsTheExtraUnits() {
        val replay = replay(2)
        replay.process(Credit("C1", 1, h.id, bhd("10.000"), valueDay = 1, instalments = 7))
        val parts = replay.ledger.fromSource("C1").map { it.amount }
        assertEquals(bhd("10.000"), parts.reduce { x, y -> x + y })
        assertEquals(listOf("1.429", "1.429", "1.429", "1.429", "1.428", "1.428", "1.428"), parts.map { it.toString() })
    }

    @Test
    fun aCreditSmallerThanItsInstalmentCountIsRefusedBeforeAnythingIsBooked() {
        val replay = replay(2)
        assertFailsWith<DomainError> {
            replay.process(
                Credit("C1", 1, a.id, aed("0.02"), valueDay = 1, instalments = 3),
            )
        }
        assertTrue(replay.ledger.entries(a.id).isEmpty())
    }

    @Test
    fun aCreditRefusedMidSplitLeavesNoPartialEntries() {
        val replay = replay(2)
        runCatching { replay.process(Credit("C1", 1, a.id, aed("0.02"), valueDay = 1, instalments = 3)) }
        val booked = replay.ledger.fromSource("C1")
        assertTrue(booked.isEmpty() || booked.size == 3, "partial instalments booked: ${booked.map { it.amount }}")
    }

    @Test
    fun anOperationLabelledEarlierThanTheClockIsBookedTodayWithAWarning() {
        val replay = replay(6)
        replay.process(Credit("C1", 3, a.id, aed("100.00"), valueDay = 3))
        replay.process(Debit("D1", 2, a.id, aed("150.00"), valueDay = 2))
        val report = replay.closeDay()
        assertEquals(3, report.day)
        assertEquals(day(3), replay.ledger.fromSource("D1").single().bookingDate)
        assertTrue(report.account(a.id).notes.any { it.severity == WARNING && it.message.contains("D1") })
        assertEquals(1, replay.feesOn(a, 2).size)
    }

    @Test
    fun anOperationOnTheLastDayIsInsideTheCapitalization() {
        val replay = replay(3)
        val reports = replay.run(listOf(Credit("C1", 3, a.id, aed("1000.00"), valueDay = 3)))
        assertEquals(3, reports.size)
        assertEquals(aed("0.40"), reports.last().account(a.id).capitalization!!.paid)
    }

    @Test
    fun anOperationAfterTheWindowIsClosedAndReportedAtTheEndOfTheStream() {
        val replay = replay(3)
        val reports = replay.run(listOf(Debit("D1", 5, a.id, aed("10.00"), valueDay = 5)))
        assertEquals(day(5), reports.last().date)
        assertEquals(1, replay.feesOn(a, 5).size)
    }

    @Test
    fun closingDaysAfterCapitalizationNeitherThrowsNorPaysAgain() {
        val replay = replay(3)
        replay.run(listOf(Credit("C1", 1, a.id, aed("1000.00"), valueDay = 1)))
        replay.process(Debit("D1", 5, a.id, aed("10.00"), valueDay = 2))
        val after = replay.closeDay().account(a.id)
        assertEquals(1, replay.ledger.entries(a.id).count { it.type == INTEREST })
        assertNull(after.capitalization)
    }

    @Test
    fun aValueDateBeforeTheWindowAccruesNoInterestInsideThePeriod() {
        val replay = replay(2)
        val reports = replay.run(listOf(Credit("C1", 1, a.id, aed("1000.00"), valueDay = 0)))
        val dailies = reports.last().account(a.id).capitalization!!.dailies
        assertEquals(setOf(day(1), day(2)), dailies.keys)
    }

    @Test
    fun aRepeatedOperationIdIsIgnoredAndAReversalUndoesTheSingleEntry() {
        val replay = replay(6)
        replay.process(Credit("C1", 1, a.id, aed("100.00"), valueDay = 1))
        replay.process(Debit("D1", 2, a.id, aed("30.00"), valueDay = 2))
        replay.process(Debit("D1", 2, a.id, aed("30.00"), valueDay = 2))
        assertEquals(1, replay.ledger.fromSource("D1").size)
        assertTrue(
            replay.closeDay().account(a.id).notes.any {
                it.severity == WARNING && it.message.contains("already processed")
            },
        )
        replay.process(Reversal("R1", 3, a.id, reverses = "D1", reason = reason))
        assertEquals(aed("100.00"), replay.ledger.balance(a.id))
    }
}
