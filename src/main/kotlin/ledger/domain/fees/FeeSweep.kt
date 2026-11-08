package ledger.domain.fees

import ledger.domain.fees.Assessment.Charged
import ledger.domain.fees.Assessment.NoFeeDefined
import ledger.domain.fees.Assessment.Reversed
import ledger.domain.fees.Assessment.Unchanged
import ledger.domain.journal.AccountId
import ledger.domain.journal.Entry
import ledger.domain.journal.EntryType.FEE_REVERSAL
import ledger.domain.journal.EntryType.OVERDRAFT_FEE
import ledger.domain.journal.Ledger
import java.time.LocalDate

class FeeSweep(private val ledger: Ledger, private val schedule: FeeSchedule) {
    fun run(
        accountId: AccountId,
        from: LocalDate,
        today: LocalDate,
    ): SweepResult {
        val assessments = from.datesUntil(today.plusDays(1)).toList().map { date -> assess(accountId, date, today) }
        return SweepResult(
            entries = assessments.mapNotNull { assessment -> assessment.entryOrNull() },
            errors = assessments.filterIsInstance<NoFeeDefined>().map { undefined -> undefined.message },
        )
    }

    private fun assess(
        accountId: AccountId,
        date: LocalDate,
        today: LocalDate,
    ): Assessment {
        val overdrawn = ledger.softClosing(accountId, date).isNegative
        val standingFee = ledger.standingFee(accountId, date)
        return when {
            overdrawn && standingFee == null -> charge(accountId, date, today)
            !overdrawn && standingFee != null -> reverse(accountId, standingFee, today)
            else -> Unchanged
        }
    }

    private fun charge(
        accountId: AccountId,
        date: LocalDate,
        today: LocalDate,
    ): Assessment {
        val currency = ledger.account(accountId).currency
        val fee =
            schedule.overdraftFee(currency)
                ?: return NoFeeDefined("$accountId closed $date negative; no overdraft fee is defined for $currency")
        val entry =
            ledger.append(
                accountId = accountId,
                type = OVERDRAFT_FEE,
                amount = -fee,
                bookingDate = today,
                valueDate = date,
                detail = "overdraft fee for $date",
            )
        return Charged(entry)
    }

    private fun reverse(
        accountId: AccountId,
        fee: Entry,
        today: LocalDate,
    ): Assessment {
        val entry =
            ledger.append(
                accountId = accountId,
                type = FEE_REVERSAL,
                amount = -fee.amount,
                bookingDate = today,
                valueDate = fee.valueDate,
                detail = "reverses the overdraft fee for ${fee.valueDate}",
                reverses = fee.sequence,
            )
        return Reversed(entry)
    }

    private fun Assessment.entryOrNull(): Entry? =
        when (this) {
            is Charged -> entry
            is Reversed -> entry
            is NoFeeDefined, Unchanged -> null
        }
}
