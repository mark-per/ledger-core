package ledger.domain.fees

import ledger.domain.journal.Entry

data class SweepResult(val entries: List<Entry>, val errors: List<String>)
