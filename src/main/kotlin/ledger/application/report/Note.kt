package ledger.application.report

import ledger.application.report.Severity.ERROR
import ledger.application.report.Severity.WARNING

data class Note(val severity: Severity, val message: String) {
    override fun toString(): String = "${severity.name.lowercase()}: $message"

    companion object {
        fun error(message: String): Note = Note(ERROR, message)

        fun warning(message: String): Note = Note(WARNING, message)
    }
}
