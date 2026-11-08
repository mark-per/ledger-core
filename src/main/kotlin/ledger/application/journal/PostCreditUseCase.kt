package ledger.application.journal

import ledger.application.Window
import ledger.application.operation.Operation.Credit
import ledger.application.report.Note
import ledger.domain.journal.EntryType.CREDIT
import ledger.domain.journal.Ledger
import ledger.domain.journal.Posting
import java.time.LocalDate

class PostCreditUseCase(private val ledger: Ledger, private val window: Window) {
    fun execute(
        operation: Credit,
        today: LocalDate,
    ): List<Note> {
        val valueDate = window.date(operation.valueDay)
        if (operation.instalments == 1) {
            ledger.append(
                accountId = operation.account,
                type = CREDIT,
                amount = operation.amount,
                bookingDate = today,
                valueDate = valueDate,
                source = operation.id,
            )
            return emptyList()
        }
        val postings =
            operation.amount.allocate(operation.instalments).mapIndexed { index, part ->
                Posting(
                    type = CREDIT,
                    amount = part,
                    bookingDate = today,
                    valueDate = valueDate,
                    source = operation.id,
                    detail = "instalment ${index + 1} of ${operation.instalments}",
                )
            }
        ledger.appendAll(operation.account, postings)
        return emptyList()
    }
}
