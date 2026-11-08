package ledger.domain.holds

import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.holds.HoldState.EXPIRED
import ledger.domain.holds.HoldState.SETTLED
import ledger.domain.holds.SettlementOutcome.Matched
import ledger.domain.holds.SettlementOutcome.OverHold
import ledger.domain.holds.SettlementOutcome.Unmatched
import ledger.domain.holds.UnmatchedReason.ALREADY_SETTLED
import ledger.domain.holds.UnmatchedReason.AUTHORIZATION_DECLINED
import ledger.domain.holds.UnmatchedReason.AUTHORIZATION_EXPIRED
import ledger.domain.holds.UnmatchedReason.NEVER_AUTHORIZED
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.money.Currency.AED
import ledger.domain.money.Money
import ledger.domain.money.error.NonPositiveAmountError
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HoldsTest {
    private val account = Account(AccountId("ACC-001"), AED)
    private val holds = Holds(expiryDays = 7)

    private fun aed(text: String) = Money.of(text, AED)

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 1, 4).plusDays(n.toLong())

    private fun placeAuthA(): Placement =
        holds.place(
            "Auth-A",
            account,
            aed("200.00"),
            day(2),
            ledgerBalance = aed("250.00"),
        )

    private fun stateOf(authId: String) = holds.forAccount(account.id).single { it.authId == authId }.state

    @Test
    fun approvesWhenAvailableStaysAtOrAboveZero() {
        val hold = placeAuthA()

        assertEquals(ACTIVE, hold.hold.state)
        assertEquals(aed("50.00"), holds.available(account.id, aed("250.00")))
    }

    @Test
    fun approvesWhenAvailableLandsExactlyOnZero() {
        assertEquals(
            ACTIVE,
            holds.place("Auth-A", account, aed("250.00"), day(2), ledgerBalance = aed("250.00")).hold.state,
        )
    }

    @Test
    fun declinesWhenAvailableWouldGoNegativeAndHoldsNothing() {
        placeAuthA()

        val declined = holds.place("Auth-B", account, aed("90.00"), day(5), ledgerBalance = aed("250.00"))

        assertEquals(DECLINED, declined.hold.state)
        assertEquals(aed("50.00"), holds.available(account.id, aed("250.00")))
    }

    @Test
    fun anOverdraftLimitLetsAvailableGoNegativeUpToTheLimit() {
        val withLimit = Account(AccountId("ACC-009"), AED, overdraftLimit = aed("100.00"))

        assertEquals(
            ACTIVE,
            holds.place("Auth-X", withLimit, aed("350.00"), day(1), ledgerBalance = aed("250.00")).hold.state,
        )
        assertEquals(
            DECLINED,
            holds.place("Auth-Y", withLimit, aed("0.01"), day(1), ledgerBalance = aed("250.00")).hold.state,
        )
    }

    @Test
    fun aRepeatedAuthorizationIdReturnsTheOriginalDecisionAndPlacesNothing() {
        val first = placeAuthA()

        val again = placeAuthA()

        assertIs<Placement.Repeated>(again)
        assertEquals(first.hold, again.hold)
        assertEquals(1, holds.forAccount(account.id).size)
    }

    @Test
    fun aHoldExpiresSevenDaysAfterItWasPlacedAndOtherStatesAreLeftAlone() {
        placeAuthA()
        holds.place("Auth-B", account, aed("900.00"), day(2), ledgerBalance = aed("250.00"))

        assertEquals(emptyList(), holds.expire(day(8)))
        val expired = holds.expire(day(9))

        assertEquals(listOf("Auth-A"), expired.map { it.authId })
        assertEquals(EXPIRED, stateOf("Auth-A"))
        assertEquals(DECLINED, stateOf("Auth-B"))
        assertEquals(aed("250.00"), holds.available(account.id, aed("250.00")))
    }

    @Test
    fun settlingBelowTheHoldReleasesTheRestOnceRecorded() {
        placeAuthA()

        val outcome = holds.settlementFor(account.id, "Auth-A", aed("185.00"))

        assertIs<Matched>(outcome)
        assertEquals(aed("15.00"), outcome.released)
        assertEquals(ACTIVE, stateOf("Auth-A"))
        val settled = holds.recordSettlement(outcome.hold, aed("185.00"))
        assertEquals(SETTLED, settled.state)
        assertEquals(aed("185.00"), settled.settledFor)
        assertEquals(aed("250.00"), holds.available(account.id, aed("250.00")))
    }

    @Test
    fun decidingASettlementChangesNothingUntilItIsRecorded() {
        placeAuthA()

        holds.settlementFor(account.id, "Auth-A", aed("185.00"))

        assertEquals(ACTIVE, stateOf("Auth-A"))
        assertEquals(aed("50.00"), holds.available(account.id, aed("250.00")))
    }

    @Test
    fun settlingExactlyTheHoldReleasesNothing() {
        placeAuthA()

        val outcome = holds.settlementFor(account.id, "Auth-A", aed("200.00"))

        assertIs<Matched>(outcome)
        assertEquals(aed("0.00"), outcome.released)
    }

    @Test
    fun settlingAboveTheHoldIsMatchedWithTheExcess() {
        placeAuthA()

        val outcome = holds.settlementFor(account.id, "Auth-A", aed("230.00"))

        assertIs<OverHold>(outcome)
        assertEquals(aed("30.00"), outcome.excess)
    }

    @Test
    fun aSettlementMustBePositive() {
        placeAuthA()

        assertFailsWith<NonPositiveAmountError> { holds.settlementFor(account.id, "Auth-A", aed("0.00")) }
        assertEquals(ACTIVE, stateOf("Auth-A"))
    }

    @Test
    fun settlingAnUnknownAuthorizationIsUnmatched() {
        assertEquals(Unmatched(NEVER_AUTHORIZED), holds.settlementFor(account.id, "Auth-Z", aed("180.00")))
    }

    @Test
    fun anotherAccountsAuthorizationIsUnknownToThisAccount() {
        placeAuthA()
        val other = Account(AccountId("ACC-002"), AED)

        assertEquals(Unmatched(NEVER_AUTHORIZED), holds.settlementFor(other.id, "Auth-A", aed("185.00")))
        assertEquals(ACTIVE, stateOf("Auth-A"))
    }

    @Test
    fun theSameAuthorizationIdOnAnotherAccountIsANewHold() {
        placeAuthA()
        val other = Account(AccountId("ACC-002"), AED)

        val placement = holds.place("Auth-A", other, aed("50.00"), day(2), ledgerBalance = aed("100.00"))

        assertIs<Placement.Placed>(placement)
        assertEquals(1, holds.forAccount(other.id).size)
    }

    @Test
    fun settlingADeclinedAuthorizationIsUnmatchedAndLeavesItDeclined() {
        holds.place("Auth-B", account, aed("900.00"), day(5), ledgerBalance = aed("250.00"))

        assertEquals(Unmatched(AUTHORIZATION_DECLINED), holds.settlementFor(account.id, "Auth-B", aed("900.00")))
        assertEquals(DECLINED, stateOf("Auth-B"))
    }

    @Test
    fun settlingTwiceIsUnmatchedTheSecondTimeAndLeavesItSettled() {
        placeAuthA()
        val first = holds.settlementFor(account.id, "Auth-A", aed("185.00")) as Matched
        holds.recordSettlement(first.hold, aed("185.00"))

        assertEquals(Unmatched(ALREADY_SETTLED), holds.settlementFor(account.id, "Auth-A", aed("10.00")))
        assertEquals(SETTLED, stateOf("Auth-A"))
    }

    @Test
    fun settlingAnExpiredAuthorizationIsUnmatchedAndLeavesItExpired() {
        placeAuthA()
        holds.expire(day(9))

        assertEquals(Unmatched(AUTHORIZATION_EXPIRED), holds.settlementFor(account.id, "Auth-A", aed("185.00")))
        assertEquals(EXPIRED, stateOf("Auth-A"))
    }

    @Test
    fun aHoldMustBePositive() {
        assertFailsWith<NonPositiveAmountError> {
            holds.place("Auth-N", account, aed("-100.00"), day(1), ledgerBalance = aed("250.00"))
        }
    }

    @Test
    fun listsEveryHoldOfAnAccountInPlacementOrder() {
        placeAuthA()
        holds.place("Auth-B", account, aed("90.00"), day(5), ledgerBalance = aed("-335.00"))

        assertEquals(listOf("Auth-A", "Auth-B"), holds.forAccount(account.id).map { it.authId })
        assertEquals(listOf(ACTIVE, DECLINED), holds.forAccount(account.id).map { it.state })
    }
}
