package ledger.domain.interest.error

import ledger.domain.error.DomainError
import ledger.domain.journal.AccountId

class InterestAlreadyCapitalizedError(accountId: AccountId) : DomainError(
    "interest for $accountId was already capitalized",
)
