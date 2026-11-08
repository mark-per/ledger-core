package ledger.domain.journal

enum class Direction {
    IN,
    OUT,
    EITHER,
    ;

    fun allows(signum: Int): Boolean =
        when (this) {
            IN -> signum > 0
            OUT -> signum < 0
            EITHER -> signum != 0
        }
}
