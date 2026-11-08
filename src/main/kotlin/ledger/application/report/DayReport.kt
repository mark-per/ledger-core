package ledger.application.report

import ledger.domain.journal.AccountId
import java.time.LocalDate

data class DayReport(val day: Int, val date: LocalDate, val accounts: List<AccountReport>) {
    fun account(id: AccountId): AccountReport = accounts.single { it.account.id == id }
}
