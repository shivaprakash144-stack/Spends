package app.spendtracker.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.spendtracker.domain.util.Period
import app.spendtracker.domain.util.PeriodKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Quick period chips plus a "Custom" chip that opens a date-range calendar. */
@Composable
fun PeriodChips(
    selected: Period,
    onSelect: (Period) -> Unit,
    modifier: Modifier = Modifier,
    kinds: List<PeriodKind> = listOf(PeriodKind.TODAY, PeriodKind.YESTERDAY, PeriodKind.THIS_WEEK, PeriodKind.THIS_MONTH, PeriodKind.LAST_MONTH),
) {
    var showCustom by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        kinds.forEach { k ->
            FilterChip(selected = selected.kind == k, onClick = { onSelect(Period(k)) }, label = { Text(k.label) })
        }
        FilterChip(
            selected = selected.kind == PeriodKind.CUSTOM,
            onClick = { showCustom = true },
            label = { Text(if (selected.kind == PeriodKind.CUSTOM) selected.label(today) else "Pick dates") },
            leadingIcon = { Icon(Icons.Rounded.DateRange, null) },
        )
    }
    if (showCustom) {
        DateRangeDialog(
            onDismiss = { showCustom = false },
            onConfirm = { s, e -> showCustom = false; onSelect(Period(PeriodKind.CUSTOM, s, e)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val s = state.selectedStartDateMillis?.let(::utcDate) ?: return@TextButton
                    val e = state.selectedEndDateMillis?.let(::utcDate) ?: s
                    onConfirm(s, e)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = { Text("Choose dates", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

/** Material date pickers report midnight UTC. */
private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
