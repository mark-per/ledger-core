package ledger.application.holds

import ledger.application.report.Note
import ledger.domain.journal.EntryType

data class SettlementBooking(val type: EntryType, val detail: String, val notes: List<Note>)
