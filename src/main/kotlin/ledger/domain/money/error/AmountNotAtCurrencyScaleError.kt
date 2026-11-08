package ledger.domain.money.error

import ledger.domain.error.DomainError
import ledger.domain.money.Currency
import java.math.BigDecimal

class AmountNotAtCurrencyScaleError(amount: BigDecimal, currency: Currency) :
    DomainError("$amount is not at ${currency.name} scale ${currency.scale}")
