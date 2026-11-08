package ledger.domain.journal

import ledger.domain.journal.EntryType.CREDIT
import ledger.domain.journal.EntryType.DEBIT
import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.ReversalOutcome.AlreadyReversed
import ledger.domain.journal.ReversalOutcome.Booked
import ledger.domain.journal.ReversalOutcome.DifferentAccount
import ledger.domain.journal.ReversalOutcome.NotReversible
import ledger.domain.journal.ReversalOutcome.NothingBooked
import ledger.domain.journal.error.AmountSignContradictsTypeError
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import ledger.domain.money.error.CurrencyMismatchError
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerTest {
    private val acc1 = Account(AccountId("ACC-001"), AED)
    private val acc2 = Account(AccountId("ACC-002"), BHD)
    private val ledger = Ledger(listOf(acc1, acc2))

    private fun aed(text: String) = Money.of(text, AED)

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 1, 4).plusDays(n.toLong())

    @Test
    fun startsEmpty() {
        assertEquals(aed("0.00"), ledger.balance(acc1.id))
        assertEquals(Money.of("0.000", BHD), ledger.balance(acc2.id))
    }

    @Test
    fun balanceIsTheSumOfAllEntries() {
        ledger.append(acc1.id, CREDIT, aed("1200.00"), day(1), day(1), source = "E1")
        ledger.append(acc1.id, DEBIT, aed("-950.00"), day(1), day(1), source = "E2")

        assertEquals(aed("250.00"), ledger.balance(acc1.id))
    }

    @Test
    fun closingOfADayCountsOnlyEntriesValuedOnOrBeforeIt() {
        ledger.append(acc1.id, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        ledger.append(acc1.id, CREDIT, aed("400.00"), day(3), day(3), source = "E4")

        assertEquals(aed("250.00"), ledger.closing(acc1.id, day(1)))
        assertEquals(aed("250.00"), ledger.closing(acc1.id, day(2)))
        assertEquals(aed("650.00"), ledger.closing(acc1.id, day(3)))
    }

    @Test
    fun aBackDatedEntryChangesThePastDaysClosing() {
        ledger.append(acc1.id, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        assertEquals(aed("250.00"), ledger.closing(acc1.id, day(2)))

        ledger.append(acc1.id, DEBIT, aed("-620.00"), bookingDate = day(5), valueDate = day(2), source = "E7")

        assertEquals(aed("-370.00"), ledger.closing(acc1.id, day(2)))
        assertEquals(aed("250.00"), ledger.closing(acc1.id, day(1)))
    }

    @Test
    fun entriesAreNumberedInArrivalOrder() {
        val first = ledger.append(acc1.id, CREDIT, aed("1.00"), day(1), day(1), source = "E1")
        val second = ledger.append(acc1.id, CREDIT, aed("1.00"), day(1), day(1), source = "E2")

        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(listOf(first, second), ledger.entries(acc1.id))
    }

    @Test
    fun findsEntriesByTheEventTheyCameFrom() {
        val entry = ledger.append(acc1.id, DEBIT, aed("-620.00"), day(5), day(2), source = "E7")
        ledger.append(acc1.id, OVERDRAFT_FEE, aed("-25.00"), day(5), day(2), detail = "overdraft fee")

        assertEquals(listOf(entry), ledger.fromSource("E7"))
        assertTrue(ledger.fromSource("E9").isEmpty())
    }

    @Test
    fun aDebitMustBeNegativeAndACreditPositive() {
        assertFailsWith<AmountSignContradictsTypeError> { ledger.append(acc1.id, DEBIT, aed("620.00"), day(5), day(2)) }
        assertFailsWith<AmountSignContradictsTypeError> { ledger.append(acc1.id, CREDIT, aed("-1.00"), day(1), day(1)) }
        assertFailsWith<AmountSignContradictsTypeError> { ledger.append(acc1.id, CREDIT, aed("0.00"), day(1), day(1)) }
    }

    @Test
    fun anEntryMustBeInTheAccountsCurrency() {
        assertFailsWith<CurrencyMismatchError> { ledger.append(acc2.id, CREDIT, aed("1.00"), day(1), day(1)) }
    }

    @Test
    fun eachAccountHasItsOwnEntries() {
        ledger.append(acc1.id, CREDIT, aed("1.00"), day(1), day(1), source = "E1")

        assertEquals(Money.of("0.000", BHD), ledger.closing(acc2.id, day(1)))
    }

    @Test
    fun softClosingIgnoresOnlyThatDaysFeeEntries() {
        ledger.append(acc1.id, DEBIT, aed("-370.00"), day(2), day(2), source = "E7")
        ledger.append(acc1.id, OVERDRAFT_FEE, aed("-25.00"), day(5), day(2))
        ledger.append(acc1.id, CREDIT, aed("400.00"), day(3), day(3), source = "E4")

        assertEquals(aed("-370.00"), ledger.softClosing(acc1.id, day(2)))
        assertEquals(aed("-395.00"), ledger.closing(acc1.id, day(2)))
        assertEquals(aed("5.00"), ledger.softClosing(acc1.id, day(3)))
    }

    @Test
    fun closingBeforeInterestKeepsThatDaysFeesAndIgnoresOnlyThatDaysInterest() {
        ledger.append(acc1.id, CREDIT, aed("300.00"), day(1), day(1), source = "E1")
        ledger.append(acc1.id, OVERDRAFT_FEE, aed("-25.00"), day(6), day(6))
        ledger.append(acc1.id, INTEREST, aed("0.79"), day(6), day(6))

        assertEquals(aed("275.00"), ledger.closingBeforeInterest(acc1.id, day(6)))
        assertEquals(aed("300.00"), ledger.softClosing(acc1.id, day(6)))
        assertEquals(aed("275.79"), ledger.closing(acc1.id, day(6)))
        assertEquals(aed("275.79"), ledger.closingBeforeInterest(acc1.id, day(7)))
    }

    @Test
    fun aStandingFeeIsAChargedFeeWithoutAReversal() {
        assertNull(ledger.standingFee(acc1.id, day(2)))
        val fee = ledger.append(acc1.id, OVERDRAFT_FEE, aed("-25.00"), day(5), day(2))
        assertEquals(fee, ledger.standingFee(acc1.id, day(2)))
        ledger.append(acc1.id, FEE_REVERSAL, aed("25.00"), day(6), day(2), reverses = fee.sequence)
        assertNull(ledger.standingFee(acc1.id, day(2)))
    }

    @Test
    fun reportsTheEarliestValueDateTouchedSinceTheLastClose() {
        ledger.append(acc1.id, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        ledger.markClosed(acc1.id)
        assertNull(ledger.earliestValueDateSinceLastClose(acc1.id))

        ledger.append(acc1.id, CREDIT, aed("400.00"), day(3), day(3), source = "E4")
        ledger.append(acc1.id, DEBIT, aed("-620.00"), day(5), day(2), source = "E7")

        assertEquals(day(2), ledger.earliestValueDateSinceLastClose(acc1.id))
    }

    @Test
    fun aReversalMirrorsEveryEntryOfTheEventAtTheOriginalValueDate() {
        ledger.append(acc1.id, CREDIT, aed("3.00"), day(6), day(5), source = "E10", detail = "instalment 1 of 2")
        ledger.append(acc1.id, CREDIT, aed("3.00"), day(6), day(5), source = "E10", detail = "instalment 2 of 2")

        val outcome = ledger.reverse("E10", acc1.id, bookingDate = day(7), by = "E11", reason = Reason("duplicate"))

        assertIs<Booked>(outcome)
        assertEquals(listOf(aed("-3.00"), aed("-3.00")), outcome.entries.map { it.amount })
        assertTrue(outcome.entries.all { it.valueDate == day(5) && it.bookingDate == day(7) && it.source == "E11" })
        assertEquals(listOf(1, 2), outcome.entries.map { it.reverses })
        assertEquals(aed("0.00"), ledger.balance(acc1.id))
    }

    @Test
    fun aReversalIsRefusedWhenNothingWasBookedTwiceOrAcrossAccountsOrOfAReversal() {
        ledger.append(acc1.id, DEBIT, aed("-620.00"), day(5), day(2), source = "E7")
        assertEquals(NothingBooked, ledger.reverse("E8", acc1.id, day(6), by = "X", reason = Reason("r")))
        assertEquals(DifferentAccount, ledger.reverse("E7", acc2.id, day(6), by = "X", reason = Reason("r")))

        assertIs<Booked>(ledger.reverse("E7", acc1.id, day(6), by = "E9", reason = Reason("r")))
        assertEquals(AlreadyReversed, ledger.reverse("E7", acc1.id, day(6), by = "X", reason = Reason("r")))
        assertEquals(NotReversible, ledger.reverse("E9", acc1.id, day(6), by = "X", reason = Reason("r")))
    }
}
