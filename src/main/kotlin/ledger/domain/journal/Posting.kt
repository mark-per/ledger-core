package ledger.domain.journal

import ledger.domain.money.Money
import java.time.LocalDate

data class Posting(
    val type: EntryType,
    val amount: Money,
    val bookingDate: LocalDate,
    val valueDate: LocalDate,
    val source: String? = null,
    val detail: String? = null,
    val reverses: Int? = null,
)
