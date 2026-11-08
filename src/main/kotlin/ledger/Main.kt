package ledger

import ledger.application.Scenario.OPERATIONS
import ledger.application.Scenario.printer
import ledger.application.Scenario.replay

fun main() {
    val reports = replay().run(OPERATIONS)
    print(printer().render(reports))
}
