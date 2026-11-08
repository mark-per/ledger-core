package ledger.domain.journal

sealed interface ReversalOutcome {
    data class Booked(val entries: List<Entry>) : ReversalOutcome

    data object NothingBooked : ReversalOutcome

    data object NotReversible : ReversalOutcome

    data object AlreadyReversed : ReversalOutcome

    data object DifferentAccount : ReversalOutcome
}
