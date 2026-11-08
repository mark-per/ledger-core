package ledger.application.holds

import ledger.application.Window
import ledger.application.operation.Operation.Settlement
import ledger.application.report.Note
import ledger.domain.holds.Holds
import ledger.domain.holds.SettlementOutcome
import ledger.domain.holds.SettlementOutcome.Matched
import ledger.domain.holds.SettlementOutcome.OverHold
import ledger.domain.holds.SettlementOutcome.Unmatched
import ledger.domain.holds.UnmatchedReason
import ledger.domain.holds.UnmatchedReason.ALREADY_SETTLED
import ledger.domain.holds.UnmatchedReason.AUTHORIZATION_DECLINED
import ledger.domain.holds.UnmatchedReason.AUTHORIZATION_EXPIRED
import ledger.domain.holds.UnmatchedReason.NEVER_AUTHORIZED
import ledger.domain.journal.EntryType.SETTLEMENT
import ledger.domain.journal.EntryType.UNMATCHED_SETTLEMENT
import ledger.domain.journal.Ledger
import java.time.LocalDate

class SettleUseCase(private val ledger: Ledger, private val holds: Holds, private val window: Window) {
    fun execute(
        operation: Settlement,
        today: LocalDate,
    ): List<Note> {
        val outcome = holds.settlementFor(operation.account, operation.authId, operation.amount)
        val booking = bookingFor(operation, outcome)
        ledger.append(
            accountId = operation.account,
            type = booking.type,
            amount = -operation.amount,
            bookingDate = today,
            valueDate = window.date(operation.valueDay),
            source = operation.id,
            detail = booking.detail,
        )
        when (outcome) {
            is Matched -> holds.recordSettlement(outcome.hold, operation.amount)
            is OverHold -> holds.recordSettlement(outcome.hold, operation.amount)
            is Unmatched -> Unit
        }
        return booking.notes
    }

    private fun bookingFor(
        operation: Settlement,
        outcome: SettlementOutcome,
    ): SettlementBooking =
        when (outcome) {
            is Matched ->
                SettlementBooking(SETTLEMENT, detail = "settles ${operation.authId}", notes = emptyList())
            is OverHold ->
                SettlementBooking(
                    SETTLEMENT,
                    detail = "settles ${operation.authId}",
                    notes =
                        listOf(
                            Note.warning(
                                "${operation.id} settles ${operation.authId} for ${operation.amount}, " +
                                    "over its ${outcome.hold.amount} hold by ${outcome.excess}",
                            ),
                        ),
                )
            is Unmatched -> {
                val why = describe(outcome.reason)
                val note = Note.warning("${operation.id} booked as unmatched settlement; ${operation.authId} $why")
                SettlementBooking(
                    UNMATCHED_SETTLEMENT,
                    detail = "quotes ${operation.authId}, which $why",
                    notes = listOf(note),
                )
            }
        }

    private fun describe(reason: UnmatchedReason): String =
        when (reason) {
            NEVER_AUTHORIZED -> "was never authorized"
            AUTHORIZATION_DECLINED -> "was declined"
            ALREADY_SETTLED -> "was already settled"
            AUTHORIZATION_EXPIRED -> "had expired"
        }
}
