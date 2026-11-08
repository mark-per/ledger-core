package ledger.application

import ledger.application.operation.Operation
import ledger.application.operation.Operation.Authorization
import ledger.application.operation.Operation.Credit
import ledger.application.operation.Operation.Debit
import ledger.application.operation.Operation.Reversal
import ledger.application.operation.Operation.Settlement
import ledger.application.report.ReportPrinter
import ledger.domain.fees.FeeSchedule
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.Reason
import ledger.domain.money.Currency.AED
import ledger.domain.money.Currency.BHD
import ledger.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate

object Scenario {
    val DAILY_INTEREST_RATE: BigDecimal = BigDecimal("0.0004")
    const val HOLD_EXPIRY_DAYS: Int = 7
    val WINDOW = Window(anchor = LocalDate.of(2026, 1, 5), days = 6)
    val FEES = FeeSchedule(mapOf(AED to Money.of("25.00", AED)))

    val ACC_001 = Account(AccountId("ACC-001"), AED)
    val ACC_002 = Account(AccountId("ACC-002"), BHD)
    val ACCOUNTS = listOf(ACC_001, ACC_002)

    private fun aed(text: String) = Money.of(text, AED)

    private fun bhd(text: String) = Money.of(text, BHD)

    val OPERATIONS: List<Operation> =
        listOf(
            Credit("E1", 1, ACC_001.id, aed("1200.00"), valueDay = 1),
            Debit("E2", 1, ACC_001.id, aed("950.00"), valueDay = 1),
            Authorization("E3", 2, ACC_001.id, "Auth-A", aed("200.00")),
            Credit("E4", 3, ACC_001.id, aed("400.00"), valueDay = 3),
            Settlement("E5", 4, ACC_001.id, "Auth-A", aed("185.00"), valueDay = 4),
            Settlement("E6", 4, ACC_001.id, "Auth-Z", aed("180.00"), valueDay = 4),
            Debit("E7", 5, ACC_001.id, aed("620.00"), valueDay = 2),
            Authorization("E8", 5, ACC_001.id, "Auth-B", aed("90.00")),
            Reversal("E9", 6, ACC_001.id, reverses = "E7", reason = Reason("not stated by the brief")),
            Credit("E10", 5, ACC_002.id, bhd("10.000"), valueDay = 5, instalments = 3),
        )

    fun replay(): Replay = Replay(ACCOUNTS, WINDOW, FEES, DAILY_INTEREST_RATE, HOLD_EXPIRY_DAYS)

    fun printer(): ReportPrinter = ReportPrinter(WINDOW)
}
