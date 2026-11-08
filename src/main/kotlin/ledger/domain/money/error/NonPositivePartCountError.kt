package ledger.domain.money.error

import ledger.domain.error.DomainError

class NonPositivePartCountError(parts: Int) : DomainError("cannot allocate into $parts parts")
