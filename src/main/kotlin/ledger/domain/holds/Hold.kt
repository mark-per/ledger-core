package ledger.domain.holds

import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.money.Money
import java.time.LocalDate

data class Hold(
    val authId: String,
    val account: AccountId,
    val amount: Money,
    val placedOn: LocalDate,
    val expiresOn: LocalDate,
    val state: HoldState,
    val settledFor: Money? = null,
) {
    companion object {
        fun decide(
            authId: String,
            account: Account,
            amount: Money,
            availableAfterHold: Money,
            placedOn: LocalDate,
            expiresOn: LocalDate,
        ): Hold {
            val state = if (account.withinOverdraft(availableAfterHold)) ACTIVE else DECLINED
            return Hold(authId, account.id, amount, placedOn, expiresOn, state)
        }
    }
}
