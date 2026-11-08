package ledger.domain.holds

enum class UnmatchedReason {
    NEVER_AUTHORIZED,
    AUTHORIZATION_DECLINED,
    ALREADY_SETTLED,
    AUTHORIZATION_EXPIRED,
}
