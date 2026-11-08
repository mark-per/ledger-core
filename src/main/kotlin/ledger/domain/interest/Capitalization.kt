package ledger.domain.interest

import ledger.domain.journal.Entry
import ledger.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate

data class Capitalization(
    val exact: BigDecimal,
    val paid: Money,
    val dailies: Map<LocalDate, Money>,
    val entry: Entry?,
)
