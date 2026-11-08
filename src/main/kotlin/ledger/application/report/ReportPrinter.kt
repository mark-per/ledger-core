package ledger.application.report

import ledger.application.Window
import ledger.domain.holds.Hold
import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.holds.HoldState.EXPIRED
import ledger.domain.holds.HoldState.SETTLED
import ledger.domain.journal.Entry
import ledger.domain.journal.EntryType.FEE_REVERSAL

class ReportPrinter(private val window: Window) {
    fun render(reports: List<DayReport>): String = reports.joinToString("\n") { report -> render(report) }

    fun render(report: DayReport): String =
        buildString {
            appendLine("=== ${window.label(report.date)}${afterWindow(report)} ===")
            report.accounts.forEach { accountReport -> append(render(accountReport)) }
        }

    private fun render(report: AccountReport): String =
        buildString {
            appendLine("${report.account.id} (${report.account.currency})")
            appendLine(
                "  posted:         " +
                    lines(report.posted.map { entry -> posted(entry) }),
            )
            appendLine(
                "  restated:       " +
                    lines(
                        report.restated.map {
                                restatement ->
                            "${window.label(restatement.date)} ${restatement.before} -> ${restatement.after}"
                        },
                    ),
            )
            appendLine("  closing ledger: ${report.closing}")
            appendLine("  available:      ${report.available}")
            appendLine("  fees:           " + lines(report.fees.map { fee -> fee(entry = fee, report = report) }))
            appendLine("  authorizations: " + lines(report.holds.map { hold -> describe(hold) }))
            appendLine(
                "  interest:       " +
                    report.accruals.entries.joinToString(", ") { (date, accrual) ->
                        "${window.label(date)} $accrual"
                    } + capitalized(report),
            )
            appendLine("  notes:          " + lines(report.notes.map { note -> note.toString() }))
        }

    private fun posted(entry: Entry): String {
        val detail = if (entry.detail == null) "" else ", ${entry.detail}"
        return "${entry.source} ${entry.type} ${entry.amount} value ${window.label(entry.valueDate)}$detail"
    }

    private fun fee(
        entry: Entry,
        report: AccountReport,
    ): String {
        val before = report.closingsBeforeFee[entry.valueDate]
        val label = if (entry.type == FEE_REVERSAL) "day now closes at" else "closing before fee"
        val detail = if (before == null) "" else ", $label $before"
        return "${entry.type} ${entry.amount} value ${window.label(entry.valueDate)}$detail"
    }

    private fun afterWindow(report: DayReport): String = if (report.date > window.last) ", after the window" else ""

    private fun describe(hold: Hold): String =
        when (hold.state) {
            ACTIVE -> "${hold.authId} ACTIVE hold ${hold.amount}, expires ${window.label(hold.expiresOn)}"
            SETTLED -> "${hold.authId} SETTLED ${hold.settledFor} of ${hold.amount}"
            DECLINED -> "${hold.authId} DECLINED ${hold.amount}"
            EXPIRED -> "${hold.authId} EXPIRED ${hold.amount}"
        }

    private fun capitalized(report: AccountReport): String {
        val capitalization = report.capitalization ?: return " (provisional)"
        val exact = capitalization.exact.stripTrailingZeros().toPlainString()
        val entry = capitalization.entry ?: return "; exact $exact, nothing to pay"
        return "; exact $exact, paid ${capitalization.paid} booked today, value ${window.label(
            entry.valueDate,
        )}, not in today's closing"
    }

    private fun lines(items: List<String>): String =
        if (items.isEmpty()) {
            "-"
        } else {
            items.joinToString(
                "\n                  ",
            )
        }
}
