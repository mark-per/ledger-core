package ledger.domain.money.error

import ledger.domain.error.DomainError
import ledger.domain.money.Money

class NonPositiveAmountError(what: String, amount: Money) : DomainError("$what must be a positive amount, got $amount")
