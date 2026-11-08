package ledger.domain.money.error

import ledger.domain.error.DomainError
import ledger.domain.money.Money

class AmountTooSmallToSplitError(amount: Money, parts: Int) :
    DomainError("$amount has fewer minor units than the $parts parts it should be split into")
