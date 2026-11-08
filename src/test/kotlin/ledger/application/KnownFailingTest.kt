package ledger.application

import ledger.application.Scenario.ACC_001
import ledger.application.Scenario.OPERATIONS
import ledger.application.Scenario.WINDOW
import ledger.application.Scenario.replay
import ledger.application.operation.Operation.Reversal
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.EntryType.INTEREST_REVERSAL
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.Reason
import ledger.domain.money.Currency.AED
import ledger.domain.money.Money
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

// Expected to FAIL. Run with ./gradlew knownFailing; the main suite excludes this tag.
@Tag("known-failing")
class KnownFailingTest {
    private val acc1 = ACC_001.id

    private fun aed(text: String) = Money.of(text, AED)

    @Test
    fun aReversalArrivingAfterCapitalizationShouldCorrectTheInterestPaid() {
        val replay = replay()
        replay.run(OPERATIONS)
        // Day 6 has closed. 0.79 of interest was paid on a view where Day 3 closed at 650.00 and Days 4 to 6 at 285.00.

        // Day 8: the Day 3 credit E4 (+400.00) is reversed because it was posted to the wrong account. The reversal
        // takes E4's value date, Day 3.
        replay.process(Reversal("E11", 8, acc1, reverses = "E4", reason = Reason("posted to the wrong account")))
        replay.closeDay()

        // The fee sweep copes: Days 4 to 8 are now negative, so five fees are booked, valued Days 4 to 8. That part
        // holds.
        assertEquals(
            5,
            replay.ledger.entries(acc1).count { it.type == OVERDRAFT_FEE && it.bookingDate == WINDOW.date(8) },
        )

        // Interest does not cope. On the corrected view the six accruals are 0.10, 0.10, 0.10, 0.00, 0.00, 0.00 = 0.30.
        // The design says: reverse the 0.79 (INTEREST_REVERSAL) and pay 0.30 (INTEREST), both valued Day 7, so that the
        // interest entries net to 0.30. What the ledger actually holds is the original 0.79 and nothing else.
        //
        // What this reveals: fees are a function of the current view and are corrected whenever the view changes, but
        // interest is computed once and never revisited after it is paid. The INTEREST_REVERSAL type exists; no rule
        // books
        // it, because inside the six-day window nothing is paid before the last event and the case cannot arise. In
        // production it arises every period, and the rule that books it needs a policy for clawbacks it should not
        // make.
        val interestNet =
            replay.ledger.entries(acc1)
                .filter { it.type == INTEREST || it.type == INTEREST_REVERSAL }
                .fold(aed("0.00")) { sum, entry -> sum + entry.amount }
        assertEquals(aed("0.30"), interestNet)
    }
}
