package ledger.application.report

import ledger.application.Window
import ledger.domain.fees.SweepResult
import ledger.domain.holds.Holds
import ledger.domain.interest.Capitalization
import ledger.domain.interest.Interest
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.Ledger
import ledger.domain.money.Money
import java.time.LocalDate

class DayReporter(
    private val ledger: Ledger,
    private val holds: Holds,
    private val interest: Interest,
    private val window: Window,
) {
    private val lastReported = mutableMapOf<AccountId, Map<LocalDate, Money>>()

    fun report(
        account: Account,
        date: LocalDate,
        swept: SweepResult,
        capitalization: Capitalization?,
        notes: List<Note>,
    ): AccountReport {
        val closing = ledger.closing(account.id, date)
        return AccountReport(
            account = account,
            closing = closing,
            available = holds.available(account.id, closing),
            posted = ledger.entries(account.id).filter { entry -> entry.bookingDate == date && entry.source != null },
            fees = swept.entries,
            closingsBeforeFee =
                swept.entries.associate {
                        fee ->
                    fee.valueDate to ledger.closingBeforeOwnFee(account.id, fee.valueDate)
                },
            restated = restatements(account.id, date),
            holds = holds.forAccount(account.id),
            accruals = capitalization?.dailies ?: interest.provisional(account.id),
            capitalization = capitalization,
            notes = notes + swept.errors.map { message -> Note.error(message) },
        )
    }

    private fun restatements(
        accountId: AccountId,
        date: LocalDate,
    ): List<Restatement> {
        val previous = lastReported[accountId].orEmpty()
        val current =
            window.anchor.datesUntil(date.plusDays(1)).toList().associateWith {
                    pastDate ->
                ledger.closing(accountId, pastDate)
            }
        lastReported[accountId] = current
        return previous.mapNotNull { (pastDate, before) ->
            val after = current.getValue(pastDate)
            if (after == before) null else Restatement(pastDate, before = before, after = after)
        }
    }
}
