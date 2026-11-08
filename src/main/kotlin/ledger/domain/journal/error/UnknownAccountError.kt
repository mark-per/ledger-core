package ledger.domain.journal.error

import ledger.domain.error.DomainError
import ledger.domain.journal.AccountId

class UnknownAccountError(id: AccountId) : DomainError("unknown account $id")
