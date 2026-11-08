package ledger.application.journal

import ledger.application.operation.Operation.Reversal
import ledger.application.report.Note
import ledger.domain.journal.Ledger
import ledger.domain.journal.ReversalOutcome.AlreadyReversed
import ledger.domain.journal.ReversalOutcome.Booked
import ledger.domain.journal.ReversalOutcome.DifferentAccount
import ledger.domain.journal.ReversalOutcome.NotReversible
import ledger.domain.journal.ReversalOutcome.NothingBooked
import java.time.LocalDate

class ReverseUseCase(private val ledger: Ledger) {
    fun execute(
        operation: Reversal,
        today: LocalDate,
    ): List<Note> {
        val outcome =
            ledger.reverse(
                source = operation.reverses,
                accountId = operation.account,
                bookingDate = today,
                by = operation.id,
                reason = operation.reason,
            )
        return when (outcome) {
            is Booked -> emptyList()
            NothingBooked ->
                listOf(
                    Note.error("${operation.id} rejected; nothing is booked from ${operation.reverses}"),
                )
            DifferentAccount ->
                listOf(
                    Note.error("${operation.id} rejected; ${operation.reverses} belongs to another account"),
                )
            NotReversible -> listOf(Note.error("${operation.id} rejected; ${operation.reverses} is itself a reversal"))
            AlreadyReversed ->
                listOf(
                    Note.error("${operation.id} rejected; ${operation.reverses} was already reversed"),
                )
        }
    }
}
