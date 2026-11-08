package ledger.domain.money

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.Currency as IsoCurrency

class CurrencyTest {
    @Test
    fun scalesMatchIso4217() {
        Currency.entries.forEach { currency ->
            val iso = IsoCurrency.getInstance(currency.name)

            assertEquals(iso.defaultFractionDigits, currency.scale, currency.name)
        }
    }
}
