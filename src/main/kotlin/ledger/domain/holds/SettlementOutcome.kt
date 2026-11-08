package ledger.domain.holds

import ledger.domain.money.Money

sealed interface SettlementOutcome {
    data class Matched(val hold: Hold, val released: Money) : SettlementOutcome

    data class OverHold(val hold: Hold, val excess: Money) : SettlementOutcome

    data class Unmatched(val reason: UnmatchedReason) : SettlementOutcome
}
