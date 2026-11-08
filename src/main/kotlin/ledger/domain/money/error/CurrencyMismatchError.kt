package ledger.domain.money.error

import ledger.domain.error.DomainError
import ledger.domain.money.Currency

class CurrencyMismatchError(left: Currency, right: Currency) : DomainError("cannot mix ${left.name} and ${right.name}")
