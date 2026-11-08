package ledger.stress

import ledger.application.operation.Operation
import ledger.application.operation.Operation.Authorization
import ledger.application.operation.Operation.Credit
import ledger.application.operation.Operation.Debit
import ledger.application.operation.Operation.Reversal
import ledger.application.operation.Operation.Settlement
import ledger.domain.journal.Account
import ledger.domain.journal.AccountId
import ledger.domain.journal.Reason
import ledger.domain.money.Money
import java.math.BigDecimal
import java.util.Random

class StreamGenerator(private val seed: Long, private val accounts: List<Account>, private val days: Int) {
    private val random = Random(seed)
    private val idsByAccount = mutableMapOf<AccountId, MutableList<String>>()
    private val authsByAccount = mutableMapOf<AccountId, MutableList<String>>()
    private var counter = 0

    fun generate(): List<Operation> {
        val operations = mutableListOf<Operation>()
        var clock = 1
        while (clock <= days) {
            repeat(random.nextInt(6)) { operations += next(clock) }
            clock += 1 + if (random.nextInt(10) == 0) 1 else 0
        }
        return operations
    }

    private fun next(clock: Int): Operation {
        val account = accounts[random.nextInt(accounts.size)]
        val label = if (clock > 1 && random.nextInt(12) == 0) clock - 1 else clock
        val valueDay = maxOf(1, label - (if (random.nextInt(4) == 0) random.nextInt(4) else 0))
        val id = "S$seed-${++counter}"
        val operation =
            when (random.nextInt(10)) {
                0, 1, 2 -> credit(id, label, account, valueDay)
                3, 4, 5 -> Debit(id, label, account.id, amount(account, 40_000), valueDay)
                6 -> authorization(id, label, account)
                7, 8 -> settlement(id, label, account, valueDay)
                else -> reversal(id, label, account)
            }
        idsByAccount.getOrPut(account.id) { mutableListOf() } += id
        return operation
    }

    private fun credit(
        id: String,
        label: Int,
        account: Account,
        valueDay: Int,
    ): Credit {
        val instalments = if (random.nextInt(4) == 0) 2 + random.nextInt(4) else 1
        return Credit(id, label, account.id, amount(account, 50_000), valueDay, instalments)
    }

    private fun authorization(
        id: String,
        label: Int,
        account: Account,
    ): Authorization {
        val known = authsByAccount.getOrPut(account.id) { mutableListOf() }
        val authId =
            if (known.isNotEmpty() && random.nextInt(5) == 0) {
                known[random.nextInt(known.size)]
            } else {
                "A-${account.id}-$id"
            }
        known += authId
        return Authorization(id, label, account.id, authId, amount(account, 30_000))
    }

    private fun settlement(
        id: String,
        label: Int,
        account: Account,
        valueDay: Int,
    ): Settlement {
        val known = authsByAccount[account.id].orEmpty()
        val authId = if (known.isEmpty() || random.nextInt(6) == 0) "Z-$id" else known[random.nextInt(known.size)]
        return Settlement(id, label, account.id, authId, amount(account, 30_000), valueDay)
    }

    private fun reversal(
        id: String,
        label: Int,
        account: Account,
    ): Reversal {
        val known = idsByAccount[account.id].orEmpty()
        val target =
            when {
                known.isEmpty() || random.nextInt(8) == 0 -> "unknown-$id"
                random.nextInt(8) == 0 -> otherAccountId(account) ?: known.last()
                else -> known[random.nextInt(known.size)]
            }
        return Reversal(id, label, account.id, target, Reason("stress"))
    }

    private fun otherAccountId(account: Account): String? =
        idsByAccount.filterKeys { it != account.id }.values.flatten().firstOrNull()

    private fun amount(
        account: Account,
        maxUnits: Int,
    ): Money {
        val units = 10 + random.nextInt(maxUnits)
        return Money(BigDecimal.valueOf(units.toLong(), account.currency.scale), account.currency)
    }
}
