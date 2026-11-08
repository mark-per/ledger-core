package ledger.application.operation

import ledger.domain.journal.AccountId
import ledger.domain.journal.Reason
import ledger.domain.money.Money
import ledger.domain.money.error.NonPositiveAmountError

sealed interface Operation {
    val id: String
    val day: Int
    val account: AccountId

    data class Credit(
        override val id: String,
        override val day: Int,
        override val account: AccountId,
        val amount: Money,
        val valueDay: Int,
        val instalments: Int = 1,
    ) : Operation {
        init {
            if (!amount.isPositive) throw NonPositiveAmountError(id, amount)
        }
    }

    data class Debit(
        override val id: String,
        override val day: Int,
        override val account: AccountId,
        val amount: Money,
        val valueDay: Int,
    ) : Operation {
        init {
            if (!amount.isPositive) throw NonPositiveAmountError(id, amount)
        }
    }

    data class Authorization(
        override val id: String,
        override val day: Int,
        override val account: AccountId,
        val authId: String,
        val amount: Money,
    ) : Operation {
        init {
            if (!amount.isPositive) throw NonPositiveAmountError(id, amount)
        }
    }

    data class Settlement(
        override val id: String,
        override val day: Int,
        override val account: AccountId,
        val authId: String,
        val amount: Money,
        val valueDay: Int,
    ) : Operation {
        init {
            if (!amount.isPositive) throw NonPositiveAmountError(id, amount)
        }
    }

    data class Reversal(
        override val id: String,
        override val day: Int,
        override val account: AccountId,
        val reverses: String,
        val reason: Reason,
    ) : Operation
}
