package ledger.domain.money

import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.error.AmountNotAtCurrencyScaleError
import ledger.domain.money.error.CurrencyMismatchError
import ledger.domain.money.error.NonPositivePartCountError
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun aedAlwaysHasTwoDecimals() {
        assertEquals("1200.00", Money.of("1200", AED).toString())
    }

    @Test
    fun bhdAlwaysHasThreeDecimals() {
        assertEquals("10.000", Money.of("10", BHD).toString())
    }

    @Test
    fun anAmountWithTheWrongNumberOfDecimalsCannotBeCreated() {
        assertFailsWith<AmountNotAtCurrencyScaleError> { Money(BigDecimal("3.334"), AED) }
    }

    @Test
    fun parsingRefusesMoreDecimalsThanTheCurrencyHas() {
        assertFailsWith<AmountNotAtCurrencyScaleError> { Money.of("1.005", AED) }
    }

    @Test
    fun addsTwoAmounts() {
        assertEquals(Money.of("1600.00", AED), Money.of("1200.00", AED) + Money.of("400.00", AED))
    }

    @Test
    fun subtractsTwoAmounts() {
        assertEquals(Money.of("250.00", AED), Money.of("1200.00", AED) - Money.of("950.00", AED))
    }

    @Test
    fun refusesToAddDifferentCurrencies() {
        assertFailsWith<CurrencyMismatchError> { Money.of("1.00", AED) + Money.of("1.000", BHD) }
    }

    @Test
    fun knowsItsSign() {
        assertTrue(Money.of("-370.00", AED).isNegative)
        assertTrue(Money.of("250.00", AED).isPositive)
        assertFalse(Money.zero(AED).isNegative)
        assertFalse(Money.zero(AED).isPositive)
    }

    @Test
    fun roundsAnExactFigureToTheCurrency() {
        assertEquals(Money.of("0.19", AED), Money.round(BigDecimal("0.186"), AED))
        assertEquals(Money.of("0.11", AED), Money.round(BigDecimal("0.114"), AED))
        assertEquals(Money.of("0.004", BHD), Money.round(BigDecimal("0.0040"), BHD))
    }

    @Test
    fun roundsAnExactHalfToTheEvenNeighbour() {
        assertEquals(Money.of("0.12", AED), Money.round(BigDecimal("0.125"), AED))
        assertEquals(Money.of("0.14", AED), Money.round(BigDecimal("0.135"), AED))
    }

    @Test
    fun splitsTenDinarsIntoThreePartsThatSumExactly() {
        val parts = Money.of("10.000", BHD).allocate(3)

        assertEquals(listOf("3.334", "3.333", "3.333"), parts.map { it.toString() })
        assertEquals(Money.of("10.000", BHD), parts.reduce { a, b -> a + b })
    }

    @Test
    fun splitsANegativeAmountTheSameWay() {
        val parts = Money.of("-10.000", BHD).allocate(3)

        assertEquals(listOf("-3.334", "-3.333", "-3.333"), parts.map { it.toString() })
        assertEquals(Money.of("-10.000", BHD), parts.reduce { a, b -> a + b })
    }

    @Test
    fun splitsZeroAndRefusesZeroParts() {
        assertEquals(List(3) { Money.zero(AED) }, Money.zero(AED).allocate(3))
        assertFailsWith<NonPositivePartCountError> { Money.of("1.00", AED).allocate(0) }
    }
}
