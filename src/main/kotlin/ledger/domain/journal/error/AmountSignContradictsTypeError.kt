package ledger.domain.journal.error

import ledger.domain.error.DomainError
import ledger.domain.journal.EntryType
import ledger.domain.money.Money

class AmountSignContradictsTypeError(type: EntryType, amount: Money) : DomainError("$type cannot carry $amount")
