package ledger.domain.fees

import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.EntryType.CREDIT
import ledger.domain.journal.EntryType.DEBIT
import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.EntryType.REVERSAL
import ledger.domain.journal.EntryType.SETTLEMENT
import ledger.domain.journal.EntryType.UNMATCHED_SETTLEMENT
import ledger.domain.journal.Ledger
import ledger.domain.money.Currency.AED
import ledger.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeeSweepBackDatingTest {
    private val aedAccount = Account(AccountId("ACC-001"), AED)
    private val ledger = Ledger(listOf(aedAccount))
    private val sweep = FeeSweep(ledger, FeeSchedule(mapOf(AED to Money.of("25.00", AED))))
    private val acc = aedAccount.id

    private fun aed(text: String) = Money.of(text, AED)

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 1, 4).plusDays(n.toLong())

    private fun closeNormally(through: Int) = (1..through).forEach { sweep.run(acc, from = day(it), today = day(it)) }

    private fun bookDaysOneToFour() {
        ledger.append(acc, CREDIT, aed("250.00"), day(1), day(1), source = "E1")
        ledger.append(acc, CREDIT, aed("400.00"), day(3), day(3), source = "E4")
        ledger.append(acc, SETTLEMENT, aed("-185.00"), day(4), day(4), source = "E5")
        ledger.append(acc, UNMATCHED_SETTLEMENT, aed("-180.00"), day(4), day(4), source = "E6")
    }

    @Test
    fun aBackDatedDebitChargesEveryPastDayItPushesNegative() {
        bookDaysOneToFour()
        closeNormally(4)
        ledger.append(acc, DEBIT, aed("-620.00"), day(5), day(2), source = "E7")

        val result = sweep.run(acc, from = day(2), today = day(5))

        assertEquals(listOf(day(2), day(4), day(5)), result.entries.map { it.valueDate })
        assertTrue(result.entries.all { it.type == OVERDRAFT_FEE && it.bookingDate == day(5) })
        assertEquals(
            listOf("-395.00", "5.00", "-385.00", "-410.00"),
            (2..5).map { ledger.closing(acc, day(it)).toString() },
        )
    }

    @Test
    fun reversingTheCauseReversesTheFeesAtTheSameValueDates() {
        bookDaysOneToFour()
        closeNormally(4)
        ledger.append(acc, DEBIT, aed("-620.00"), day(5), day(2), source = "E7")
        sweep.run(acc, from = day(2), today = day(5))
        ledger.append(acc, REVERSAL, aed("620.00"), day(6), day(2), source = "E9")

        val result = sweep.run(acc, from = day(2), today = day(6))

        assertEquals(List(3) { FEE_REVERSAL }, result.entries.map { it.type })
        assertEquals(listOf(day(2), day(4), day(5)), result.entries.map { it.valueDate })
        assertTrue(result.entries.all { it.reverses != null })
        assertTrue((1..6).all { ledger.standingFee(acc, day(it)) == null })
        assertEquals(
            listOf("250.00", "650.00", "285.00", "285.00", "285.00"),
            (2..6).map {
                ledger.closing(acc, day(it)).toString()
            },
        )
    }

    @Test
    fun aBackDatedCreditCuresAnOverdraftAndReversesItsFee() {
        ledger.append(acc, DEBIT, aed("-10.00"), day(1), day(1), source = "E1")
        closeNormally(1)
        ledger.append(acc, CREDIT, aed("50.00"), day(2), day(1), source = "E2")

        val result = sweep.run(acc, from = day(1), today = day(2))

        assertEquals(FEE_REVERSAL, result.entries.single().type)
        assertNull(ledger.standingFee(acc, day(1)))
        assertEquals(aed("40.00"), ledger.closing(acc, day(2)))
    }
}
