package ledger.domain.fees

import ledger.domain.journal.Entry

sealed interface Assessment {
    data class Charged(val entry: Entry) : Assessment

    data class Reversed(val entry: Entry) : Assessment

    data class NoFeeDefined(val message: String) : Assessment

    data object Unchanged : Assessment
}
