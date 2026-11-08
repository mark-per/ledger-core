package ledger.domain.fees

import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.EntryType.CREDIT
import ledger.domain.journal.EntryType.DEBIT
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.Ledger
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeeSweepTest {
    private val aedAccount = Account(AccountId("ACC-001"), AED)
    private val bhdAccount = Account(AccountId("ACC-002"), BHD)
    private val ledger = Ledger(listOf(aedAccount, bhdAccount))
    private val sweep = FeeSweep(ledger, FeeSchedule(mapOf(AED to Money.of("25.00", AED))))
    private val acc = aedAccount.id

    private fun aed(text: String) = Money.of(text, AED)

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 1, 4).plusDays(n.toLong())

    @Test
    fun chargesTheFeeWhenTheDayClosesNegative() {
        ledger.append(acc, DEBIT, aed("-10.00"), day(1), day(1), source = "E1")

        val result = sweep.run(acc, from = day(1), today = day(1))

        val fee = result.entries.single()
        assertEquals(OVERDRAFT_FEE, fee.type)
        assertEquals(aed("-25.00"), fee.amount)
        assertEquals(day(1), fee.valueDate)
        assertEquals(aed("-35.00"), ledger.closing(acc, day(1)))
    }

    @Test
    fun chargesOnlyOncePerDay() {
        ledger.append(acc, DEBIT, aed("-10.00"), day(1), day(1), source = "E1")
        sweep.run(acc, from = day(1), today = day(1))

        assertTrue(sweep.run(acc, from = day(1), today = day(1)).entries.isEmpty())
        assertNotNull(ledger.standingFee(acc, day(1)))
    }

    @Test
    fun chargesNothingWhenTheDayClosesAtOrAboveZero() {
        ledger.append(acc, CREDIT, aed("10.00"), day(1), day(1), source = "E1")
        ledger.append(acc, DEBIT, aed("-10.00"), day(1), day(1), source = "E2")

        assertEquals(aed("0.00"), ledger.closing(acc, day(1)))
        assertTrue(sweep.run(acc, from = day(1), today = day(1)).entries.isEmpty())
    }

    @Test
    fun yesterdaysFeeCountsTowardsTodaysClosing() {
        ledger.append(acc, DEBIT, aed("-10.00"), day(1), day(1), source = "E1")
        sweep.run(acc, from = day(1), today = day(1))
        ledger.append(acc, CREDIT, aed("30.00"), day(2), day(2), source = "E2")

        val result = sweep.run(acc, from = day(2), today = day(2))

        assertEquals(1, result.entries.size)
        assertEquals(aed("-30.00"), ledger.closing(acc, day(2)))
    }

    @Test
    fun anOverdraftInACurrencyWithoutAFeeIsReportedNotCharged() {
        ledger.append(bhdAccount.id, DEBIT, Money.of("-1.000", BHD), day(1), day(1), source = "X1")

        val result = sweep.run(bhdAccount.id, from = day(1), today = day(1))

        assertTrue(result.entries.isEmpty())
        assertTrue(result.errors.single().contains("BHD"))
    }
}
