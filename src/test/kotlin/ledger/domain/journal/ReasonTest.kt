package ledger.domain.journal

import ledger.domain.journal.error.BlankReasonError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReasonTest {
    @Test
    fun aReasonCannotBeBlank() {
        assertFailsWith<BlankReasonError> { Reason(" ") }
    }

    @Test
    fun printsAsItsText() {
        assertEquals("duplicate", Reason("duplicate").toString())
    }
}
