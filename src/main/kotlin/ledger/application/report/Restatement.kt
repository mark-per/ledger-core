package ledger.application.report

import ledger.domain.money.Money
import java.time.LocalDate

data class Restatement(val date: LocalDate, val before: Money, val after: Money)
