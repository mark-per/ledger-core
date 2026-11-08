package ledger.application.report

import ledger.domain.holds.Hold
import ledger.domain.interest.Capitalization
import ledger.domain.journal.Account
import ledger.domain.journal.Entry
import ledger.domain.money.Money
import java.time.LocalDate

data class AccountReport(
    val account: Account,
    val closing: Money,
    val available: Money,
    val posted: List<Entry>,
    val fees: List<Entry>,
    val closingsBeforeFee: Map<LocalDate, Money>,
    val restated: List<Restatement>,
    val holds: List<Hold>,
    val accruals: Map<LocalDate, Money>,
    val capitalization: Capitalization?,
    val notes: List<Note>,
)
