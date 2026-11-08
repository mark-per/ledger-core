package ledger.domain.journal

import ledger.domain.journal.Direction.EITHER
import ledger.domain.journal.Direction.IN
import ledger.domain.journal.Direction.OUT

enum class EntryType(val direction: Direction) {
    CREDIT(IN),
    DEBIT(OUT),
    SETTLEMENT(OUT),
    UNMATCHED_SETTLEMENT(OUT),
    REVERSAL(EITHER),
    OVERDRAFT_FEE(OUT),
    FEE_REVERSAL(IN),
    INTEREST(IN),
    INTEREST_REVERSAL(OUT),
}
