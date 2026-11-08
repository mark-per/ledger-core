package ledger.stress

import ledger.application.Window
import ledger.domain.fees.FeeSchedule
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import org.junit.jupiter.api.Tag
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("stress")
class InvariantStressTest {
    private val fees = FeeSchedule(mapOf(AED to Money.of("25.00", AED)))
    private val rate = BigDecimal("0.0004")
    private val anchor = LocalDate.of(2026, 1, 5)

    private val twoAccounts =
        listOf(
            Account(AccountId("AED-0"), AED),
            Account(AccountId("BHD-0"), BHD),
        )

    private val threeAccounts =
        listOf(
            Account(AccountId("AED-0"), AED),
            Account(AccountId("AED-L"), AED, Money.of("500.00", AED)),
            Account(AccountId("BHD-L"), BHD, Money.of("50.000", BHD)),
        )

    private fun runSeed(
        seed: Long,
        accounts: List<Account>,
        days: Int,
    ): StressHarness {
        val window = Window(anchor, days)
        val operations = StreamGenerator(seed, accounts, days).generate()
        val harness = StressHarness(accounts, window, fees, rate)
        val reports = harness.run(operations)
        assertEquals(days, reports.size)
        val again = StressHarness(accounts, window, fees, rate).run(operations)
        assertEquals(reports, again, "replay of seed $seed is not deterministic")
        if (seed % 100L == 0L || days == 90) println("stress: seed $seed ${harness.summary()}")
        return harness
    }

    @Test
    fun sixDayStreamsHoldEveryInvariant() {
        val violations = (1L..300L).sumOf { seed -> runSeed(seed, twoAccounts, 6).strictFeeViolations }
        println("stress: 300 six-day seeds, days with more than one OVERDRAFT_FEE ever booked: $violations")
    }

    @Test
    fun thirtyDayStreamsHoldEveryInvariant() {
        var violations = 0
        val millis =
            measureTimeMillis {
                violations = (100L..119L).sumOf { seed -> runSeed(seed, threeAccounts, 30).strictFeeViolations }
            }
        println("stress: 20 thirty-day seeds x2 runs in $millis ms, days with a second fee: $violations")
    }

    @Test
    fun ninetyDayStreamsHoldEveryInvariant() {
        var violations = 0
        val millis =
            measureTimeMillis {
                violations = (900L..904L).sumOf { seed -> runSeed(seed, threeAccounts, 90).strictFeeViolations }
            }
        println("stress: 5 ninety-day seeds x2 runs in $millis ms, days with a second fee: $violations")
    }
}
