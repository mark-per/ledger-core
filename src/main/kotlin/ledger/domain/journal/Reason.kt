package ledger.domain.journal

import ledger.domain.journal.error.BlankReasonError

@JvmInline
value class Reason(val text: String) {
    init {
        if (text.isBlank()) throw BlankReasonError()
    }

    override fun toString(): String = text
}
