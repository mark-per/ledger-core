package ledger.domain.holds

sealed interface Placement {
    val hold: Hold

    data class Placed(override val hold: Hold) : Placement

    data class Repeated(override val hold: Hold) : Placement
}
