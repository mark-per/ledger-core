package ledger.domain.holds

import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.holds.HoldState.EXPIRED
import ledger.domain.holds.HoldState.SETTLED
import ledger.domain.holds.Placement.Placed
import ledger.domain.holds.Placement.Repeated
import ledger.domain.holds.SettlementOutcome.Matched
import ledger.domain.holds.SettlementOutcome.OverHold
import ledger.domain.holds.SettlementOutcome.Unmatched
import ledger.domain.holds.UnmatchedReason.ALREADY_SETTLED
import ledger.domain.holds.UnmatchedReason.AUTHORIZATION_DECLINED
import ledger.domain.holds.UnmatchedReason.AUTHORIZATION_EXPIRED
import ledger.domain.holds.UnmatchedReason.NEVER_AUTHORIZED
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.money.Currency
import ledger.domain.money.Money
import java.time.LocalDate

class Holds(private val expiryDays: Int) {
    private val holds = mutableListOf<Hold>()

    fun place(
        authId: String,
        account: Account,
        amount: Money,
        date: LocalDate,
        ledgerBalance: Money,
    ): Placement {
        val earlier = holds.firstOrNull { it.authId == authId }
        if (earlier != null) return Repeated(earlier)
        val hold =
            Hold.decide(
                authId = authId,
                account = account,
                amount = amount,
                availableAfterHold = available(account.id, ledgerBalance) - amount,
                placedOn = date,
                expiresOn = date.plusDays(expiryDays.toLong()),
            )
        holds += hold
        return Placed(hold)
    }

    fun settle(
        authId: String,
        amount: Money,
    ): SettlementOutcome {
        val index = holds.indexOfFirst { it.authId == authId }
        if (index < 0) return Unmatched(NEVER_AUTHORIZED)
        return when (holds[index].state) {
            DECLINED -> Unmatched(AUTHORIZATION_DECLINED)
            SETTLED -> Unmatched(ALREADY_SETTLED)
            EXPIRED -> Unmatched(AUTHORIZATION_EXPIRED)
            ACTIVE -> consume(index, amount)
        }
    }

    fun expire(date: LocalDate): List<Hold> {
        val expired = mutableListOf<Hold>()
        holds.forEachIndexed { index, hold ->
            if (hold.state == ACTIVE && hold.expiresOn <= date) {
                holds[index] = hold.copy(state = EXPIRED)
                expired += holds[index]
            }
        }
        return expired
    }

    fun available(
        accountId: AccountId,
        ledgerBalance: Money,
    ): Money = ledgerBalance - activeTotal(accountId, ledgerBalance.currency)

    fun forAccount(accountId: AccountId): List<Hold> = holds.filter { it.account == accountId }

    private fun consume(
        index: Int,
        amount: Money,
    ): SettlementOutcome {
        val hold = holds[index]
        val settled = hold.copy(state = SETTLED, settledFor = amount)
        holds[index] = settled
        if (amount > hold.amount) return OverHold(settled, amount - hold.amount)
        return Matched(settled, hold.amount - amount)
    }

    private fun activeTotal(
        accountId: AccountId,
        currency: Currency,
    ): Money =
        forAccount(accountId)
            .filter { it.state == ACTIVE }
            .fold(Money.zero(currency)) { total, hold -> total + hold.amount }
}
