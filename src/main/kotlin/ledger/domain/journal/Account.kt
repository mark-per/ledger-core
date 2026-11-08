package ledger.domain.journal

import ledger.domain.journal.error.NegativeOverdraftLimitError
import ledger.domain.money.Currency
import ledger.domain.money.Money
import ledger.domain.money.error.CurrencyMismatchError

data class Account(
    val id: AccountId,
    val currency: Currency,
    val overdraftLimit: Money = Money.zero(currency),
) {
    init {
        if (overdraftLimit.currency != currency) throw CurrencyMismatchError(currency, overdraftLimit.currency)
        if (overdraftLimit.isNegative) throw NegativeOverdraftLimitError(overdraftLimit, id)
    }

    fun withinOverdraft(available: Money): Boolean = available >= -overdraftLimit
}
