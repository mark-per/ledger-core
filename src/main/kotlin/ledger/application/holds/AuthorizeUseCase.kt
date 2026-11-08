package ledger.application.holds

import ledger.application.operation.Operation.Authorization
import ledger.application.report.Note
import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.Holds
import ledger.domain.holds.Placement.Placed
import ledger.domain.holds.Placement.Repeated
import ledger.domain.journal.Account
import ledger.domain.journal.Ledger
import ledger.domain.money.Money
import java.time.LocalDate

class AuthorizeUseCase(private val ledger: Ledger, private val holds: Holds) {
    fun execute(
        operation: Authorization,
        today: LocalDate,
    ): List<Note> {
        val account = ledger.account(operation.account)
        val balance = ledger.closing(operation.account, today)
        val available = holds.available(operation.account, balance)
        val placement =
            holds.place(
                authId = operation.authId,
                account = account,
                amount = operation.amount,
                date = today,
                ledgerBalance = balance,
            )
        return when (placement) {
            is Repeated -> listOf(repeated(operation, placement))
            is Placed ->
                if (placement.hold.state == ACTIVE) {
                    emptyList()
                } else {
                    listOf(
                        declined(operation, available, account),
                    )
                }
        }
    }

    private fun repeated(
        operation: Authorization,
        placement: Repeated,
    ): Note =
        Note.warning(
            "${operation.id} repeats ${operation.authId}; " +
                "original decision ${placement.hold.state} stands, no new hold",
        )

    private fun declined(
        operation: Authorization,
        available: Money,
        account: Account,
    ): Note =
        Note.error(
            "${operation.id} declined; ${operation.authId} for ${operation.amount} " +
                "would take available $available below the overdraft limit of ${account.overdraftLimit}",
        )
}
