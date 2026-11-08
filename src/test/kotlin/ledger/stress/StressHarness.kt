package ledger.stress

import ledger.application.Replay
import ledger.application.Window
import ledger.application.operation.Operation
import ledger.application.operation.Operation.Authorization
import ledger.application.operation.Operation.Credit
import ledger.application.report.DayReport
import ledger.domain.fees.FeeSchedule
import ledger.domain.holds.HoldState
import ledger.domain.holds.HoldState.ACTIVE
import ledger.domain.holds.HoldState.DECLINED
import ledger.domain.holds.HoldState.EXPIRED
import ledger.domain.holds.HoldState.SETTLED
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.Entry
import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.INTEREST
import ledger.domain.journal.EntryType.INTEREST_REVERSAL
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.EntryType.REVERSAL
import ledger.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StressHarness(
    private val accounts: List<Account>,
    private val window: Window,
    private val schedule: FeeSchedule,
    private val rate: BigDecimal,
    expiryDays: Int = 7,
) {
    val replay = Replay(accounts, window, schedule, rate, expiryDays)
    val reports = mutableListOf<DayReport>()
    private val daysChargedAgainAfterACure = mutableSetOf<Pair<AccountId, LocalDate>>()
    val daysChargedAgain: Int get() = daysChargedAgainAfterACure.size
    private val snapshots = mutableMapOf<AccountId, List<Entry>>()
    private val firstDecision = mutableMapOf<String, HoldState>()
    private val credits = mutableListOf<Credit>()

    fun process(operation: Operation) {
        val expectation = (operation as? Authorization)?.let { expectAuthorization(it) }
        val holdsBefore = replay.holds.forAccount(operation.account).size
        replay.process(operation)
        if (operation is Credit) credits += operation
        expectation?.let { checkAuthorization(operation as Authorization, it, holdsBefore) }
        checkJournal()
        checkHoldTransitions()
    }

    fun closeDay(): DayReport {
        val closingDate = window.date(replay.day)
        val report = replay.closeDay()
        reports += report
        checkJournal()
        checkHoldTransitions()
        checkFees(closingDate)
        checkAvailable(report, closingDate)
        report.accounts.forEach { accountReport ->
            accountReport.capitalization?.let { checkCapitalization(accountReport.account.id, it.dailies, it.paid) }
        }
        return report
    }

    fun run(operations: List<Operation>): List<DayReport> {
        val all = mutableListOf<DayReport>()
        operations.forEach { operation ->
            while (replay.day < operation.day) all += closeDay()
            process(operation)
        }
        while (window.date(replay.day) <= window.last) all += closeDay()
        checkInstalments()
        return all
    }

    fun summary(): String {
        val entries = accounts.flatMap { replay.ledger.entries(it.id) }
        val holds = accounts.flatMap { replay.holds.forAccount(it.id) }
        return entries.groupingBy { it.type }.eachCount().toString() + " " +
            holds.groupingBy { it.state }.eachCount().toString()
    }

    private data class AuthExpectation(val repeatedState: HoldState?, val approve: Boolean)

    private fun expectAuthorization(operation: Authorization): AuthExpectation {
        val existing = replay.holds.forAccount(operation.account).firstOrNull { it.authId == operation.authId }
        val today = window.date(replay.day)
        val account = replay.ledger.account(operation.account)
        val closing = replay.ledger.closing(operation.account, today)
        val active =
            replay.holds
                .forAccount(operation.account)
                .filter { it.state == ACTIVE }
                .fold(Money.zero(account.currency)) { sum, hold -> sum + hold.amount }
        val available = closing - active
        return AuthExpectation(existing?.state, available - operation.amount >= -account.overdraftLimit)
    }

    private fun checkAuthorization(
        operation: Authorization,
        expectation: AuthExpectation,
        holdsBefore: Int,
    ) {
        val holds = replay.holds.forAccount(operation.account)
        if (expectation.repeatedState != null) {
            assertEquals(holdsBefore, holds.size, "repeated ${operation.authId} placed a hold")
            assertEquals(expectation.repeatedState, holds.first { it.authId == operation.authId }.state)
            return
        }
        val hold = holds.last()
        assertEquals(operation.authId, hold.authId)
        assertEquals(if (expectation.approve) ACTIVE else DECLINED, hold.state, "decision for ${operation.id}")
        firstDecision[operation.authId] = hold.state
    }

    private fun checkHoldTransitions() {
        accounts.flatMap { replay.holds.forAccount(it.id) }.forEach { hold ->
            assertEquals(hold.account.let { replay.ledger.account(it).currency }, hold.amount.currency)
            assertEquals(hold.amount.currency.scale, hold.amount.amount.scale())
            val first = firstDecision[hold.authId] ?: return@forEach
            val allowed =
                when (first) {
                    ACTIVE -> setOf(ACTIVE, SETTLED, EXPIRED)
                    else -> setOf(first)
                }
            assertTrue(hold.state in allowed, "${hold.authId} went from $first to ${hold.state}")
        }
    }

    private fun checkJournal() {
        val today = window.date(replay.day)
        accounts.forEach { account ->
            val entries = replay.ledger.entries(account.id)
            val previous = snapshots[account.id].orEmpty()
            assertTrue(entries.size >= previous.size, "journal shrank for ${account.id}")
            assertEquals(previous, entries.take(previous.size), "journal prefix changed for ${account.id}")
            snapshots[account.id] = entries
            entries.forEach { entry ->
                assertEquals(account.currency, entry.amount.currency)
                assertEquals(account.currency.scale, entry.amount.amount.scale(), "scale of $entry")
            }
            val expected =
                entries
                    .filter { it.valueDate <= today }
                    .fold(Money.zero(account.currency)) { sum, entry -> sum + entry.amount }
            assertEquals(expected, replay.ledger.closing(account.id, today))
            val lastValue = entries.maxOfOrNull { it.valueDate }
            if (lastValue != null) {
                assertEquals(replay.ledger.closing(account.id, lastValue), replay.ledger.balance(account.id))
            }
            checkLinks(entries)
        }
    }

    private fun checkLinks(entries: List<Entry>) {
        val bySequence = entries.associateBy { it.sequence }
        entries.filter { it.reverses != null }.forEach { reversal ->
            val original = assertNotNull(bySequence[reversal.reverses], "dangling link from $reversal")
            assertEquals(original.account, reversal.account)
            assertEquals(original.valueDate, reversal.valueDate)
            assertEquals(-original.amount, reversal.amount)
            when (reversal.type) {
                FEE_REVERSAL -> assertEquals(OVERDRAFT_FEE, original.type)
                REVERSAL ->
                    assertTrue(
                        original.source != null && original.type !in setOf(REVERSAL, OVERDRAFT_FEE, INTEREST),
                        "REVERSAL linked to $original",
                    )
                INTEREST_REVERSAL -> assertEquals(INTEREST, original.type)
                else -> error("unexpected link on $reversal")
            }
        }
        entries.filter { it.type in setOf(FEE_REVERSAL, REVERSAL) }.forEach {
            assertNotNull(it.reverses, "unlinked $it")
        }
    }

    private fun checkFees(closed: LocalDate) {
        accounts.forEach { account ->
            val entries = replay.ledger.entries(account.id)
            val first = entries.minOfOrNull { it.valueDate } ?: return@forEach
            val hasFee = schedule.overdraftFee(account.currency) != null
            first.datesUntil(closed.plusDays(1)).forEach { date ->
                val sameDay = entries.filter { it.valueDate == date }
                val charged = sameDay.count { it.type == OVERDRAFT_FEE }
                val reversed = sameDay.count { it.type == FEE_REVERSAL }
                assertTrue(charged - reversed in 0..1, "${account.id} $date net fees ${charged - reversed}")
                if (charged > 1) daysChargedAgainAfterACure += account.id to date
                val negative = replay.ledger.closingBeforeOwnFee(account.id, date).isNegative
                val standing = replay.ledger.standingFee(account.id, date) != null
                if (hasFee) {
                    assertEquals(negative, standing, "${account.id} $date negative=$negative standing=$standing")
                } else {
                    assertEquals(0, charged)
                }
            }
        }
    }

    private fun checkAvailable(
        report: DayReport,
        date: LocalDate,
    ) {
        report.accounts.forEach { accountReport ->
            val id = accountReport.account.id
            val closing = replay.ledger.closing(id, date)
            assertEquals(closing, accountReport.closing)
            val active =
                replay.holds
                    .forAccount(id)
                    .filter { it.state == ACTIVE }
                    .fold(Money.zero(closing.currency)) { sum, hold -> sum + hold.amount }
            assertEquals(closing - active, accountReport.available)
        }
    }

    fun checkCapitalization(
        accountId: AccountId,
        dailies: Map<LocalDate, Money>,
        paid: Money,
    ) {
        val currency = replay.ledger.account(accountId).currency
        assertEquals(dailies.values.fold(Money.zero(currency)) { sum, daily -> sum + daily }, paid)
        dailies.forEach { (date, daily) ->
            val closing = replay.ledger.closing(accountId, date)
            val expected =
                if (closing.isPositive) Money.round(closing.amount.multiply(rate), currency) else Money.zero(currency)
            assertEquals(expected, daily, "$accountId daily for $date on closing $closing")
        }
    }

    private fun checkInstalments() {
        credits.filter { it.instalments > 1 }.forEach { credit ->
            val parts =
                replay.ledger.fromSource(
                    credit.id,
                ).filter { it.account == credit.account }.take(credit.instalments)
            assertEquals(credit.instalments, parts.size)
            assertEquals(
                credit.amount,
                parts.fold(Money.zero(credit.amount.currency)) { sum, part -> sum + part.amount },
            )
            val units = parts.map { it.amount.amount.unscaledValue().toLong() }
            assertTrue(units.max() - units.min() <= 1, "${credit.id} parts $units")
            assertEquals(units.sortedDescending(), units, "${credit.id} extra units not first: $units")
        }
    }
}
