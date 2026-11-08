package ledger.domain.journal.error

import ledger.domain.error.DomainError
import ledger.domain.journal.AccountId
import ledger.domain.money.Money

class NegativeOverdraftLimitError(limit: Money, account: AccountId) :
    DomainError("$limit is a negative overdraft limit for $account")
