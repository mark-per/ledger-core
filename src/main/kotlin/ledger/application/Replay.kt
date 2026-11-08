package ledger.application

import ledger.application.close.CloseDayUseCase
import ledger.application.holds.AuthorizeUseCase
import ledger.application.holds.SettleUseCase
import ledger.application.journal.PostCreditUseCase
import ledger.application.journal.PostDebitUseCase
import ledger.application.journal.ReverseUseCase
import ledger.application.operation.Operation
import ledger.application.operation.Operation.Authorization
import ledger.application.operation.Operation.Credit
import ledger.application.operation.Operation.Debit
import ledger.application.operation.Operation.Reversal
import ledger.application.operation.Operation.Settlement
import ledger.application.report.DayReport
import ledger.application.report.DayReporter
import ledger.application.report.Note
import ledger.domain.fees.FeeSchedule
import ledger.domain.fees.FeeSweep
import ledger.domain.holds.Holds
import ledger.domain.interest.Interest
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.Ledger
import java.math.BigDecimal
import java.time.LocalDate

class Replay(
    accounts: List<Account>,
    private val window: Window,
    schedule: FeeSchedule,
    dailyRate: BigDecimal,
    holdExpiryDays: Int,
) {
    val ledger = Ledger(accounts)
    val holds = Holds(holdExpiryDays)
    var day: Int = 1
        private set

    private val postCredit = PostCreditUseCase(ledger, window)
    private val postDebit = PostDebitUseCase(ledger, window)
    private val authorize = AuthorizeUseCase(ledger, holds)
    private val settle = SettleUseCase(ledger, holds, window)
    private val reverse = ReverseUseCase(ledger)
    private val interest = Interest(ledger, dailyRate)
    private val closeDay =
        CloseDayUseCase(
            accounts = accounts,
            ledger = ledger,
            holds = holds,
            sweep = FeeSweep(ledger, schedule),
            interest = interest,
            window = window,
            reporter = DayReporter(ledger, holds, interest, window),
        )

    private val reports = mutableListOf<DayReport>()
    private val notes = mutableMapOf<AccountId, MutableList<Note>>()

    private val today: LocalDate get() = window.date(day)

    fun run(operations: List<Operation>): List<DayReport> {
        operations.forEach(::process)
        val lastDayToClose = maxOf(window.last, window.date(operations.maxOfOrNull { it.day } ?: 1))
        while (today <= lastDayToClose) closeDay()
        return reports.toList()
    }

    fun process(operation: Operation) {
        while (day < operation.day) closeDay()
        if (operation.day < day) {
            note(
                operation.account,
                Note.warning(
                    "${operation.id} is labelled Day ${operation.day} but arrived on Day $day; " +
                        "booked $today with its stated value date",
                ),
            )
        }
        warnIfValuedBeforeTheWindow(operation)
        val raised =
            when (operation) {
                is Credit -> postCredit.execute(operation, today)
                is Debit -> postDebit.execute(operation, today)
                is Authorization -> authorize.execute(operation, today)
                is Settlement -> settle.execute(operation, today)
                is Reversal -> reverse.execute(operation, today)
            }
        raised.forEach { raised -> note(operation.account, raised) }
    }

    fun closeDay(): DayReport {
        val report = closeDay.execute(day, today, notes)
        reports += report
        notes.clear()
        day += 1
        return report
    }

    private fun warnIfValuedBeforeTheWindow(operation: Operation) {
        val valueDay =
            when (operation) {
                is Credit -> operation.valueDay
                is Debit -> operation.valueDay
                is Settlement -> operation.valueDay
                is Authorization, is Reversal -> return
            }
        if (valueDay >= 1) return
        note(
            operation.account,
            Note.warning("${operation.id} is valued Day $valueDay, before the window; it counts from Day 1"),
        )
    }

    private fun note(
        accountId: AccountId,
        note: Note,
    ) {
        notes.getOrPut(accountId) { mutableListOf() } += note
    }
}
