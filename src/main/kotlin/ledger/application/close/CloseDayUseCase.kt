package ledger.application.close

import ledger.application.Window
import ledger.application.report.AccountReport
import ledger.application.report.DayReport
import ledger.application.report.DayReporter
import ledger.application.report.Note
import ledger.domain.fees.FeeSweep
import ledger.domain.holds.Holds
import ledger.domain.interest.Interest
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.Ledger
import java.time.LocalDate

class CloseDayUseCase(
    private val accounts: List<Account>,
    private val ledger: Ledger,
    private val holds: Holds,
    private val sweep: FeeSweep,
    private val interest: Interest,
    private val window: Window,
    private val reporter: DayReporter,
) {
    fun execute(
        day: Int,
        date: LocalDate,
        notes: Map<AccountId, List<Note>>,
    ): DayReport {
        val expiryNotes = expireHolds(date)
        val reports =
            accounts.map { account ->
                val notesForAccount = notes[account.id].orEmpty() + expiryNotes[account.id].orEmpty()
                close(account = account, date = date, notes = notesForAccount)
            }
        return DayReport(day = day, date = date, accounts = reports)
    }

    private fun expireHolds(date: LocalDate): Map<AccountId, List<Note>> =
        holds.expire(date).groupBy({ hold -> hold.account }) { hold ->
            Note.warning("${hold.authId} expired unsettled, hold of ${hold.amount} released")
        }

    private fun close(
        account: Account,
        date: LocalDate,
        notes: List<Note>,
    ): AccountReport {
        val from = minOf(ledger.earliestValueDateSinceLastClose(account.id) ?: date, date)
        val swept = sweep.run(account.id, from = from, today = date)
        interest.recompute(account.id, from = from, today = date)
        val capitalization = if (date == window.last) interest.capitalize(account.id, bookingDate = date) else null
        ledger.markClosed(account.id)
        return reporter.report(
            account = account,
            date = date,
            swept = swept,
            capitalization = capitalization,
            notes = notes,
        )
    }
}
