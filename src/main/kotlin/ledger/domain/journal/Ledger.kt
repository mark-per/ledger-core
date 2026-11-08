package ledger.domain.journal

import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.EntryType.REVERSAL
import ledger.domain.journal.ReversalOutcome.AlreadyReversed
import ledger.domain.journal.ReversalOutcome.Booked
import ledger.domain.journal.ReversalOutcome.DifferentAccount
import ledger.domain.journal.ReversalOutcome.NotReversible
import ledger.domain.journal.ReversalOutcome.NothingBooked
import ledger.domain.journal.error.AmountSignContradictsTypeError
import ledger.domain.journal.error.UnknownAccountError
import ledger.domain.money.Money
import ledger.domain.money.error.CurrencyMismatchError
import java.time.LocalDate

class Ledger(accounts: List<Account>) {
    private val byId: Map<AccountId, Account> = accounts.associateBy { it.id }
    private val entries = mutableListOf<Entry>()
    private val reversedSources = mutableSetOf<String>()
    private val closedThrough = mutableMapOf<AccountId, Int>()

    fun account(id: AccountId): Account = byId[id] ?: throw UnknownAccountError(id)

    fun append(
        accountId: AccountId,
        type: EntryType,
        amount: Money,
        bookingDate: LocalDate,
        valueDate: LocalDate,
        source: String? = null,
        detail: String? = null,
        reverses: Int? = null,
    ): Entry {
        if (amount.currency != account(accountId).currency) {
            throw CurrencyMismatchError(
                account(accountId).currency,
                amount.currency,
            )
        }
        if (!type.direction.allows(amount.amount.signum())) throw AmountSignContradictsTypeError(type, amount)
        val entry = Entry(nextSequence(), accountId, type, amount, bookingDate, valueDate, source, detail, reverses)
        entries += entry
        return entry
    }

    fun reverse(
        source: String,
        accountId: AccountId,
        bookingDate: LocalDate,
        by: String,
        reason: Reason,
    ): ReversalOutcome {
        val originals = fromSource(source)
        if (originals.isEmpty()) return NothingBooked
        if (originals.any { it.account != accountId }) return DifferentAccount
        if (originals.any { it.type == REVERSAL }) return NotReversible
        if (!reversedSources.add(source)) return AlreadyReversed
        val reversals =
            originals.map { original ->
                append(
                    accountId = accountId,
                    type = REVERSAL,
                    amount = -original.amount,
                    bookingDate = bookingDate,
                    valueDate = original.valueDate,
                    source = by,
                    detail = "reverses $source, $reason",
                    reverses = original.sequence,
                )
            }
        return Booked(reversals)
    }

    fun entries(accountId: AccountId): List<Entry> = entries.filter { it.account == accountId }

    fun fromSource(source: String): List<Entry> = entries.filter { it.source == source }

    fun balance(accountId: AccountId): Money = sum(accountId, entries(accountId))

    fun closing(
        accountId: AccountId,
        date: LocalDate,
    ): Money = sum(accountId, entries(accountId).filter { it.valueDate <= date })

    fun closingBeforeOwnFee(
        accountId: AccountId,
        date: LocalDate,
    ): Money = sum(accountId, entries(accountId).filter { it.valueDate <= date && !isOwnDayFee(it, date) })

    fun standingFee(
        accountId: AccountId,
        date: LocalDate,
    ): Entry? {
        val sameDay = entries(accountId).filter { it.valueDate == date }
        val charged = sameDay.count { it.type == OVERDRAFT_FEE }
        val reversed = sameDay.count { it.type == FEE_REVERSAL }
        val nothingStanding = charged == reversed
        if (nothingStanding) return null
        return sameDay.last { it.type == OVERDRAFT_FEE }
    }

    fun earliestValueDateSinceLastClose(accountId: AccountId): LocalDate? {
        val closed = closedThrough[accountId] ?: 0
        val sinceClose = entries(accountId).filter { it.sequence > closed }
        return sinceClose.minOfOrNull { it.valueDate }
    }

    fun markClosed(accountId: AccountId) {
        closedThrough[accountId] = entries(accountId).lastOrNull()?.sequence ?: 0
    }

    private fun nextSequence(): Int = entries.size + 1

    private fun isOwnDayFee(
        entry: Entry,
        date: LocalDate,
    ): Boolean = entry.valueDate == date && (entry.type == OVERDRAFT_FEE || entry.type == FEE_REVERSAL)

    private fun sum(
        accountId: AccountId,
        selected: List<Entry>,
    ): Money = selected.fold(Money.zero(account(accountId).currency)) { total, entry -> total + entry.amount }
}
