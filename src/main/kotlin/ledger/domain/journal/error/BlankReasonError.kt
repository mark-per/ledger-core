package ledger.domain.journal.error

import ledger.domain.error.DomainError

class BlankReasonError : DomainError("a reason cannot be blank")
