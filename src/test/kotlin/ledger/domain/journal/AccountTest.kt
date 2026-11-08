package ledger.domain.journal

import ledger.domain.journal.error.NegativeOverdraftLimitError
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import ledger.domain.money.error.CurrencyMismatchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountTest {
    @Test
    fun theOverdraftLimitDefaultsToZeroInTheAccountsCurrency() {
        assertEquals(Money.zero(BHD), Account(AccountId("ACC-002"), BHD).overdraftLimit)
    }

    @Test
    fun theOverdraftLimitMustBeNonNegativeAndInTheAccountsCurrency() {
        assertFailsWith<NegativeOverdraftLimitError> { Account(AccountId("X"), AED, Money.of("-1.00", AED)) }
        assertFailsWith<CurrencyMismatchError> { Account(AccountId("X"), AED, Money.of("1.000", BHD)) }
    }

    @Test
    fun anAvailableBalanceIsWithinOverdraftDownToMinusTheLimitInclusive() {
        val account = Account(AccountId("X"), AED, Money.of("100.00", AED))

        assertTrue(account.withinOverdraft(Money.of("0.00", AED)))
        assertTrue(account.withinOverdraft(Money.of("-100.00", AED)))
        assertFalse(account.withinOverdraft(Money.of("-100.01", AED)))
    }

    @Test
    fun withNoLimitAvailableMustStayAtOrAboveZero() {
        val account = Account(AccountId("X"), AED)

        assertTrue(account.withinOverdraft(Money.of("0.00", AED)))
        assertFalse(account.withinOverdraft(Money.of("-0.01", AED)))
    }
}
