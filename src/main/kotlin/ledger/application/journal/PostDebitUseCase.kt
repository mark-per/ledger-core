package ledger.application.journal

import ledger.application.Window
import ledger.application.operation.Operation.Debit
import ledger.application.report.Note
import ledger.domain.journal.EntryType.DEBIT
import ledger.domain.journal.Ledger
import java.time.LocalDate

class PostDebitUseCase(private val ledger: Ledger, private val window: Window) {
    fun execute(
        operation: Debit,
        today: LocalDate,
    ): List<Note> {
        ledger.append(
            accountId = operation.account,
            type = DEBIT,
            amount = -operation.amount,
            bookingDate = today,
            valueDate = window.date(operation.valueDay),
            source = operation.id,
        )
        return emptyList()
    }
}
