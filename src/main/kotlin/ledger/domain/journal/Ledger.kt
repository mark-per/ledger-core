package ledger.domain.journal

import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.EntryType.INTEREST_REVERSAL
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
    ): Entry =
        appendAll(
            accountId,
            listOf(Posting(type, amount, bookingDate, valueDate, source, detail, reverses)),
        ).single()

    fun appendAll(
        accountId: AccountId,
        postings: List<Posting>,
    ): List<Entry> {
        postings.forEach { posting -> validate(accountId, posting) }
        return postings.map { posting -> write(accountId, posting) }
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
        val postings =
            originals.map { original ->
                Posting(
                    type = REVERSAL,
                    amount = -original.amount,
                    bookingDate = bookingDate,
                    valueDate = original.valueDate,
                    source = by,
                    detail = "reverses $source, $reason",
                    reverses = original.sequence,
                )
            }
        val reversals = appendAll(accountId, postings)
        return Booked(reversals)
    }

    fun entries(accountId: AccountId): List<Entry> = entries.filter { it.account == accountId }

    fun fromSource(source: String): List<Entry> = entries.filter { it.source == source }

    fun balance(accountId: AccountId): Money = sum(accountId, entries(accountId))

    fun closing(
        accountId: AccountId,
        date: LocalDate,
    ): Money = sum(accountId, entries(accountId).filter { it.valueDate <= date })

    fun softClosing(
        accountId: AccountId,
        date: LocalDate,
    ): Money = sum(accountId, entries(accountId).filter { it.valueDate <= date && !isEndOfDayPosting(it, date) })

    fun closingBeforeInterest(
        accountId: AccountId,
        date: LocalDate,
    ): Money = sum(accountId, entries(accountId).filter { it.valueDate <= date && !isOwnDayInterest(it, date) })

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

    private fun validate(
        accountId: AccountId,
        posting: Posting,
    ) {
        val currency = account(accountId).currency
        if (posting.amount.currency != currency) throw CurrencyMismatchError(currency, posting.amount.currency)
        if (!posting.type.direction.allows(
                posting.amount.amount.signum(),
            )
        ) {
            throw AmountSignContradictsTypeError(posting.type, posting.amount)
        }
    }

    private fun write(
        accountId: AccountId,
        posting: Posting,
    ): Entry {
        val entry =
            Entry(
                sequence = entries.size + 1,
                account = accountId,
                type = posting.type,
                amount = posting.amount,
                bookingDate = posting.bookingDate,
                valueDate = posting.valueDate,
                source = posting.source,
                detail = posting.detail,
                reverses = posting.reverses,
            )
        entries += entry
        return entry
    }

    private fun isEndOfDayPosting(
        entry: Entry,
        date: LocalDate,
    ): Boolean = entry.valueDate == date && entry.type in END_OF_DAY_POSTINGS

    private fun isOwnDayInterest(
        entry: Entry,
        date: LocalDate,
    ): Boolean = entry.valueDate == date && entry.type in INTEREST_POSTINGS

    private fun sum(
        accountId: AccountId,
        selected: List<Entry>,
    ): Money = selected.fold(Money.zero(account(accountId).currency)) { total, entry -> total + entry.amount }

    private companion object {
        val INTEREST_POSTINGS = setOf(INTEREST, INTEREST_REVERSAL)
        val END_OF_DAY_POSTINGS = setOf(OVERDRAFT_FEE, FEE_REVERSAL) + INTEREST_POSTINGS
    }
}
