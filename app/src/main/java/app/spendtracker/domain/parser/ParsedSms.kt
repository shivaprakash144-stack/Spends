package app.spendtracker.domain.parser

import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType

data class ParsedSms(
    val bankId: String,
    val bankName: String,
    val isUpiApp: Boolean,
    val amountPaise: Long,
    val type: TxnType,
    val mode: TxnMode,
    val accountType: AccountType,
    val last4: String?,
    val merchantRaw: String?,
    val merchantName: String,
    val upiRef: String?,
    val balancePaise: Long?,
    val availableLimitPaise: Long?,
    val timestamp: Long,
    val patternId: String,
)

sealed interface ParseResult {
    data class Parsed(val sms: ParsedSms) : ParseResult
    data class Ignored(val reason: String) : ParseResult
    data class Failed(val reason: String) : ParseResult
}
