package ledger.domain.interest

import ledger.domain.interest.error.InterestAlreadyCapitalizedError
import ledger.domain.journal.AccountId
import ledger.domain.journal.Entry
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.Ledger
import ledger.domain.money.Currency
import ledger.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate

class Interest(private val ledger: Ledger, private val dailyRate: BigDecimal) {
    private val accruals = mutableMapOf<AccountId, MutableMap<LocalDate, BigDecimal>>()
    private val capitalized = mutableSetOf<AccountId>()

    fun recompute(
        accountId: AccountId,
        from: LocalDate,
        today: LocalDate,
    ) {
        val byDate = accruals.getOrPut(accountId) { sortedMapOf() }
        from.datesUntil(today.plusDays(1)).forEach { date -> byDate[date] = exact(accountId, date) }
    }

    fun provisional(accountId: AccountId): Map<LocalDate, Money> {
        val currency = currency(accountId)
        return accruals[accountId].orEmpty().mapValues { (_, exact) -> Money.round(exact, currency) }
    }

    fun capitalize(
        accountId: AccountId,
        bookingDate: LocalDate,
    ): Capitalization {
        if (!capitalized.add(accountId)) throw InterestAlreadyCapitalizedError(accountId)
        val currency = currency(accountId)
        val exactByDate = accruals[accountId].orEmpty()
        val exactTotal = exactByDate.values.fold(BigDecimal.ZERO) { sum, accrual -> sum + accrual }
        val dailies = exactByDate.mapValues { (_, exact) -> Money.round(exact, currency) }
        val paid = dailies.values.fold(Money.zero(currency)) { sum, daily -> sum + daily }
        val entry = bookPayment(accountId, paid, bookingDate, period = exactByDate.keys)
        return Capitalization(exactTotal, paid, dailies, entry)
    }

    private fun bookPayment(
        accountId: AccountId,
        paid: Money,
        bookingDate: LocalDate,
        period: Set<LocalDate>,
    ): Entry? {
        if (!paid.isPositive) return null
        return ledger.append(
            accountId = accountId,
            type = INTEREST,
            amount = paid,
            bookingDate = bookingDate,
            valueDate = bookingDate.plusDays(VALUE_DATE_LAG_DAYS),
            detail = "interest ${period.first()} to ${period.last()}",
        )
    }

    private fun exact(
        accountId: AccountId,
        date: LocalDate,
    ): BigDecimal {
        val closing = ledger.closing(accountId, date)
        if (!closing.isPositive) return BigDecimal.ZERO
        return closing.amount.multiply(dailyRate)
    }

    private fun currency(accountId: AccountId): Currency = ledger.account(accountId).currency

    private companion object {
        const val VALUE_DATE_LAG_DAYS = 1L
    }
}
