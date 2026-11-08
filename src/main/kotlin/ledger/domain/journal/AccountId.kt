package ledger.domain.journal

@JvmInline
value class AccountId(val value: String) {
    override fun toString(): String = value
}
