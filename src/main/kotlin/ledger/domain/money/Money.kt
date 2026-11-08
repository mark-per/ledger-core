package ledger.domain.money

import ledger.domain.money.error.AmountNotAtCurrencyScaleError
import ledger.domain.money.error.AmountTooSmallToSplitError
import ledger.domain.money.error.CurrencyMismatchError
import ledger.domain.money.error.NonPositivePartCountError
import java.math.BigDecimal
import java.math.RoundingMode

data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {
    init {
        if (amount.scale() != currency.scale) throw AmountNotAtCurrencyScaleError(amount, currency)
    }

    val isNegative: Boolean get() = amount.signum() < 0
    val isPositive: Boolean get() = amount.signum() > 0

    operator fun plus(other: Money): Money = Money(amount + sameCurrency(other).amount, currency)

    operator fun minus(other: Money): Money = Money(amount - sameCurrency(other).amount, currency)

    operator fun unaryMinus(): Money = Money(amount.negate(), currency)

    override fun compareTo(other: Money): Int = amount.compareTo(sameCurrency(other).amount)

    fun allocate(parts: Int): List<Money> {
        if (parts <= 0) throw NonPositivePartCountError(parts)
        if (isNegative) return (-this).allocate(parts).map { part -> -part }
        if (inUnits() < parts) throw AmountTooSmallToSplitError(this, parts)
        val share = Money(amount.divide(BigDecimal(parts), currency.scale, RoundingMode.DOWN), currency)
        val leftover = this - share * parts
        val unit = unit(currency)
        val partsGettingAnExtraUnit = leftover.inUnits()
        return List(parts) { index -> if (index < partsGettingAnExtraUnit) share + unit else share }
    }

    private operator fun times(count: Int): Money = Money(amount.multiply(BigDecimal(count)), currency)

    private fun inUnits(): Int = amount.movePointRight(currency.scale).intValueExact()

    override fun toString(): String = amount.toPlainString()

    private fun sameCurrency(other: Money): Money {
        if (other.currency != currency) throw CurrencyMismatchError(currency, other.currency)
        return other
    }

    companion object {
        val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

        fun of(
            text: String,
            currency: Currency,
        ): Money {
            val exact = BigDecimal(text)
            val atScale =
                runCatching {
                    exact.setScale(currency.scale, RoundingMode.UNNECESSARY)
                }.getOrElse { throw AmountNotAtCurrencyScaleError(exact, currency) }
            return Money(atScale, currency)
        }

        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO.setScale(currency.scale), currency)

        fun round(
            exact: BigDecimal,
            currency: Currency,
        ): Money = Money(exact.setScale(currency.scale, ROUNDING), currency)

        fun unit(currency: Currency): Money = Money(BigDecimal.ONE.movePointLeft(currency.scale), currency)
    }
}
