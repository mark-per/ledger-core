package ledger.domain.fees

import ledger.domain.money.Currency
import ledger.domain.money.Money

class FeeSchedule(private val overdraft: Map<Currency, Money>) {
    fun overdraftFee(currency: Currency): Money? = overdraft[currency]
}
