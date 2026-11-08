package ledger.application

import ledger.application.Scenario.OPERATIONS
import ledger.application.Scenario.printer
import ledger.application.Scenario.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportPrinterTest {
    private val rendered = RENDERED

    private fun day(n: Int): String = rendered.substringAfter("=== Day $n ").substringBefore("=== Day ${n + 1} ")

    @Test
    fun printsOneBlockPerDayWithEveryRequiredLine() {
        assertEquals(6, Regex("=== Day \\d ").findAll(rendered).count())
        listOf(
            "posted:",
            "restated:",
            "closing ledger:",
            "available:",
            "fees:",
            "authorizations:",
            "interest:",
            "notes:",
        ).forEach {
                label ->
            assertEquals(12, Regex(Regex.escape(label)).findAll(rendered).count(), label)
        }
    }

    @Test
    fun dayFiveShowsTheFeesWithTheClosingBeforeEachFee() {
        assertTrue(day(5).contains("OVERDRAFT_FEE -25.00 value Day 2 (2026-01-06), closing before fee -370.00"))
        assertTrue(day(5).contains("closing ledger: -410.00"))
        assertTrue(day(5).contains("Auth-B DECLINED 90.00"))
        assertTrue(day(5).contains("error: E8 declined"))
    }

    @Test
    fun daySixShowsTheInterestAsPaidTodayValuedTomorrowAndNotInTheClosing() {
        assertTrue(
            day(6).contains("exact 0.802, paid 0.79 booked today, value Day 7 (2026-01-11), not in today's closing"),
        )
        assertTrue(day(6).contains("closing ledger: 285.00"))
        assertTrue(day(6).contains("E10 CREDIT 3.334 value Day 5 (2026-01-09), instalment 1 of 3"))
        assertTrue(day(6).contains("warning: E10 is labelled Day 5 but arrived on Day 6"))
    }

    private companion object {
        val RENDERED: String by lazy { printer().render(replay().run(OPERATIONS)).also(::println) }
    }
}
