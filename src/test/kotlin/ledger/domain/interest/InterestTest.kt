package ledger.domain.interest

import ledger.domain.interest.error.InterestAlreadyCapitalizedError
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.EntryType.CREDIT
import ledger.domain.journal.EntryType.DEBIT
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.EntryType.SETTLEMENT
import ledger.domain.journal.EntryType.UNMATCHED_SETTLEMENT
import ledger.domain.journal.Ledger
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InterestTest {
    private val aedAccount = Account(AccountId("ACC-001"), AED)
    private val bhdAccount = Account(AccountId("ACC-002"), BHD)
    private val ledger = Ledger(listOf(aedAccount, bhdAccount))
    private val interest = Interest(ledger, BigDecimal("0.0004"))
    private val acc = aedAccount.id

    private fun aed(text: String) = Money.of(text, AED)

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 1, 4).plusDays(n.toLong())

    private fun bookFinalBalances() {
        ledger.append(acc, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        ledger.append(acc, CREDIT, aed("400.00"), day(3), day(3), source = "E4")
        ledger.append(acc, SETTLEMENT, aed("-185.00"), day(4), day(4), source = "E5")
        ledger.append(acc, UNMATCHED_SETTLEMENT, aed("-180.00"), day(4), day(4), source = "E6")
    }

    @Test
    fun accruesOnPositiveClosingsOnly() {
        ledger.append(acc, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        ledger.append(acc, DEBIT, aed("-620.00"), day(2), day(2), source = "E7")

        interest.recompute(acc, from = day(1), today = day(2))

        assertEquals(aed("0.10"), interest.provisional(acc).getValue(day(1)))
        assertEquals(aed("0.00"), interest.provisional(acc).getValue(day(2)))
    }

    @Test
    fun aBackDatedEntryChangesEarlierDaysAccrualsWhenRecomputed() {
        ledger.append(acc, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        interest.recompute(acc, from = day(1), today = day(2))
        ledger.append(acc, DEBIT, aed("-620.00"), day(5), day(2), source = "E7")

        interest.recompute(acc, from = day(2), today = day(5))

        assertEquals(aed("0.10"), interest.provisional(acc).getValue(day(1)))
        assertEquals(aed("0.00"), interest.provisional(acc).getValue(day(2)))
    }

    @Test
    fun paysTheSumOfTheRoundedDailiesAndReportsTheExactFigure() {
        bookFinalBalances()
        interest.recompute(acc, from = day(1), today = day(6))

        val result = interest.capitalize(acc, bookingDate = day(6))

        assertEquals(BigDecimal("0.802"), result.exact.stripTrailingZeros())
        assertEquals(aed("0.79"), result.paid)
        assertEquals(
            listOf("0.10", "0.10", "0.26", "0.11", "0.11", "0.11"),
            result.dailies.values.map { it.toString() },
        )
        assertEquals(result.paid, result.dailies.values.reduce { a, b -> a + b })
    }

    @Test
    fun theInterestEntryIsBookedAndValuedOnTheLastDay() {
        bookFinalBalances()
        interest.recompute(acc, from = day(1), today = day(6))

        val entry = interest.capitalize(acc, bookingDate = day(6)).entry!!

        assertEquals(INTEREST, entry.type)
        assertEquals(aed("0.79"), entry.amount)
        assertEquals(day(6), entry.bookingDate)
        assertEquals(day(6), entry.valueDate)
        assertEquals(aed("285.00"), ledger.softClosing(acc, day(6)))
        assertEquals(aed("285.00"), ledger.closingBeforeInterest(acc, day(6)))
        assertEquals(aed("285.79"), ledger.closing(acc, day(6)))
    }

    @Test
    fun accruesAtThreeDecimalsForBhd() {
        ledger.append(bhdAccount.id, CREDIT, Money.of("10.000", BHD), day(6), day(5), source = "E10")
        interest.recompute(bhdAccount.id, from = day(1), today = day(6))

        val result = interest.capitalize(bhdAccount.id, bookingDate = day(6))

        assertEquals(Money.of("0.008", BHD), result.paid)
        assertEquals(
            listOf("0.000", "0.000", "0.000", "0.000", "0.004", "0.004"),
            result.dailies.values.map { it.toString() },
        )
    }

    @Test
    fun booksNothingWhenThereIsNothingToPay() {
        interest.recompute(acc, from = day(1), today = day(6))

        assertNull(interest.capitalize(acc, bookingDate = day(6)).entry)
        assertEquals(0, ledger.entries(acc).size)
    }

    @Test
    fun capitalizesOnlyOncePerAccount() {
        bookFinalBalances()
        interest.recompute(acc, from = day(1), today = day(6))
        interest.capitalize(acc, bookingDate = day(6))

        assertFailsWith<InterestAlreadyCapitalizedError> { interest.capitalize(acc, bookingDate = day(6)) }
    }
}
