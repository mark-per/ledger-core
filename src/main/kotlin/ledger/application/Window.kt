package ledger.application

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Window(val anchor: LocalDate, val days: Int) {
    val last: LocalDate = anchor.plusDays(days - 1L)

    fun date(day: Int): LocalDate = anchor.plusDays(day - 1L)

    fun label(date: LocalDate): String = "Day ${ChronoUnit.DAYS.between(anchor, date) + 1} ($date)"
}
