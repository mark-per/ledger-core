package ledger.domain.error

abstract class DomainError(message: String) : RuntimeException(message)
