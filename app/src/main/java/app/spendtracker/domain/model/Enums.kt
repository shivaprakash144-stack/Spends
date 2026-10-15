package app.spendtracker.domain.model

enum class TxnType { DEBIT, CREDIT }

enum class TxnMode { UPI, CARD, NEFT, IMPS, ATM, CASH, OTHER }

enum class TxnSource { SMS, MANUAL, EMAIL, NOTIFICATION, STATEMENT }

/** Where an incoming message came from; decides how it is parsed. */
enum class MessageKind { SMS, EMAIL, NOTIFICATION }

enum class AccountType { SAVINGS, CREDIT_CARD, WALLET, CASH }

enum class CategoryType { EXPENSE, INCOME, TRANSFER }

enum class ParseStatus { PARSED, IGNORED, FAILED }

enum class RuleSource { DEFAULT, USER }

fun TxnMode.label(): String = when (this) {
    TxnMode.UPI -> "UPI"
    TxnMode.CARD -> "Card"
    TxnMode.NEFT -> "NEFT"
    TxnMode.IMPS -> "IMPS"
    TxnMode.ATM -> "ATM"
    TxnMode.CASH -> "Cash"
    TxnMode.OTHER -> "Other"
}

fun AccountType.label(): String = when (this) {
    AccountType.SAVINGS -> "Bank account"
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.WALLET -> "Wallet"
    AccountType.CASH -> "Cash"
}
