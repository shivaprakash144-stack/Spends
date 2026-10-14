package app.spendtracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.TxnRow
import app.spendtracker.domain.model.CategoryType
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.model.label
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.theme.AmountStyle
import app.spendtracker.ui.theme.LocalMoneyColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Icons & colours ----------

fun categoryIcon(name: String?): ImageVector = when (name) {
    "restaurant" -> Icons.Rounded.Restaurant
    "grocery" -> Icons.Rounded.LocalGroceryStore
    "shopping" -> Icons.Rounded.ShoppingBag
    "transport" -> Icons.Rounded.DirectionsCar
    "fuel" -> Icons.Rounded.LocalGasStation
    "bolt" -> Icons.Rounded.Bolt
    "subscriptions" -> Icons.Rounded.Subscriptions
    "home" -> Icons.Rounded.Home
    "health" -> Icons.Rounded.MedicalServices
    "movie" -> Icons.Rounded.Movie
    "flight" -> Icons.Rounded.Flight
    "loan" -> Icons.Rounded.AccountBalance
    "invest" -> Icons.AutoMirrored.Rounded.TrendingUp
    "cash" -> Icons.Rounded.Payments
    "transfer" -> Icons.Rounded.SwapHoriz
    "income" -> Icons.Rounded.Savings
    else -> Icons.Rounded.Category
}

val ICON_CHOICES = listOf(
    "restaurant", "grocery", "shopping", "transport", "fuel", "bolt", "subscriptions", "home",
    "health", "movie", "flight", "loan", "invest", "cash", "transfer", "income", "help",
)

val COLOUR_CHOICES = listOf(
    0xFFE8705AL, 0xFF5BAE6EL, 0xFFD9A23BL, 0xFF4C8DD6L, 0xFFC4573FL, 0xFF8A7BD8L, 0xFFC368B0L,
    0xFF7C8B5AL, 0xFF3FA7A0L, 0xFFE0567EL, 0xFF2E9CC9L, 0xFF9C6B4EL, 0xFF2F8F6BL, 0xFF6F7C85L,
)

fun Long?.toColor(): Color = if (this == null) Color(0xFFA0A4A8) else Color(this.toInt())

// ---------- Aggregates ----------

/** Rows that count towards spend/income: not excluded, not transfers. */
fun TxnRow.counts(): Boolean = !t.excluded && categoryType != CategoryType.TRANSFER

fun List<TxnRow>.spend(): Long = filter { it.counts() && it.t.type == TxnType.DEBIT }.sumOf { it.t.amountPaise }
fun List<TxnRow>.income(): Long = filter { it.counts() && it.t.type == TxnType.CREDIT }.sumOf { it.t.amountPaise }

data class CategorySlice(val categoryId: Long?, val name: String, val icon: String?, val colour: Color, val amount: Long)

fun List<TxnRow>.byCategory(): List<CategorySlice> =
    filter { it.counts() && it.t.type == TxnType.DEBIT }
        .groupBy { it.t.categoryId }
        .map { (id, rows) ->
            val f = rows.first()
            CategorySlice(id, f.categoryName ?: "Uncategorised", f.categoryIcon, f.categoryColour.toColor(), rows.sumOf { it.t.amountPaise })
        }
        .sortedByDescending { it.amount }

data class MerchantTotal(val name: String, val amount: Long, val count: Int, val colour: Color)

fun List<TxnRow>.topMerchants(limit: Int = 5): List<MerchantTotal> =
    filter { it.counts() && it.t.type == TxnType.DEBIT }
        .groupBy { it.t.merchantName.lowercase() }
        .map { (_, rows) -> MerchantTotal(rows.first().t.merchantName, rows.sumOf { it.t.amountPaise }, rows.size, rows.first().categoryColour.toColor()) }
        .sortedByDescending { it.amount }
        .take(limit)

private val zone: ZoneId get() = ZoneId.systemDefault()
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)
fun formatDay(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dayFmt)

// ---------- Building blocks ----------

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun CategoryBadge(icon: String?, colour: Color, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(colour.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(icon), contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun AmountText(
    paise: Long,
    type: TxnType?,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    excluded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val money = LocalMoneyColors.current
    val colour = when {
        excluded -> MaterialTheme.colorScheme.onSurfaceVariant
        type == TxnType.CREDIT -> money.credit
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = if (type == TxnType.CREDIT) "+" else ""
    Text(
        prefix + Money.format(paise),
        style = style.merge(AmountStyle),
        color = colour,
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
fun TransactionItem(row: TxnRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(row.categoryIcon, row.categoryColour.toColor())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.t.merchantName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                add(row.categoryName ?: "Uncategorised")
                add(row.t.mode.label())
                row.accountLast4?.let { add("••$it") }
                add(formatTime(row.t.timestamp))
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            AmountText(row.t.amountPaise, row.t.type, MaterialTheme.typography.titleSmall, excluded = row.t.excluded)
            if (row.t.excluded) {
                Text("Excluded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (row.categoryKey == "uncategorised") {
                Text("Review", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
fun MonthSwitcher(label: String, onPrev: () -> Unit, onNext: () -> Unit, canNext: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month") }
        Text(label, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext, enabled = canNext) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month") }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Savings, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Charts (Canvas) ----------

/** Donut with rounded gaps between segments; animates in. */
@Composable
fun DonutChart(slices: List<CategorySlice>, modifier: Modifier = Modifier, thickness: Dp = 18.dp, center: @Composable () -> Unit = {}) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(slices) { started = true }
    val progress by animateFloatAsState(if (started) 1f else 0f, tween(900), label = "donut")
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = thickness.toPx()
            val d = size.minDimension - stroke
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
            val total = slices.sumOf { it.amount }.toFloat()
            if (total <= 0f) return@Canvas
            val gap = if (slices.size > 1) 6f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = (s.amount / total) * 360f * progress
                if (sweep - gap > 0.5f) {
                    drawArc(s.colour, start + gap / 2, sweep - gap, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                start += sweep
            }
        }
        center()
    }
}

/** Horizontal bar rows: label, amount, proportional bar. */
@Composable
fun CategoryBars(slices: List<CategorySlice>, modifier: Modifier = Modifier, onClick: ((CategorySlice) -> Unit)? = null) {
    val max = (slices.maxOfOrNull { it.amount } ?: 1L).coerceAtLeast(1L)
    val total = slices.sumOf { it.amount }.coerceAtLeast(1L)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        slices.forEach { s ->
            Row(
                Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick(s) } else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(s.icon, s.colour, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Money.format(s.amount), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Box(Modifier.fillMaxWidth(s.amount.toFloat() / max).height(6.dp).clip(CircleShape).background(s.colour))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("${(s.amount * 100 / total)}% of spend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Cumulative or daily line with a soft gradient fill. [compare] draws a dashed-looking
 * secondary line (e.g. last month) underneath.
 */
@Composable
fun TrendLine(
    values: List<Long>,
    modifier: Modifier = Modifier,
    compare: List<Long>? = null,
    colour: Color = MaterialTheme.colorScheme.primary,
) {
    val compareColour = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val all = values + (compare ?: emptyList())
        val maxV = (all.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val n = maxOf(values.size, compare?.size ?: 0).coerceAtLeast(2)
        val stepX = size.width / (n - 1)
        fun y(v: Long) = size.height - (v / maxV) * size.height * 0.92f
        for (i in 0..3) {
            val gy = size.height * i / 3f
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        compare?.takeIf { it.size > 1 }?.let { c ->
            val p = Path()
            c.forEachIndexed { i, v -> if (i == 0) p.moveTo(0f, y(v)) else p.lineTo(i * stepX, y(v)) }
            drawPath(p, compareColour.copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        if (values.size > 1) {
            val line = Path()
            values.forEachIndexed { i, v ->
                val x = i * stepX
                if (i == 0) line.moveTo(x, y(v)) else {
                    val px = (i - 1) * stepX
                    val mid = (px + x) / 2
                    line.cubicTo(mid, y(values[i - 1]), mid, y(v), x, y(v))
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo((values.size - 1) * stepX, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(colour.copy(alpha = 0.28f), colour.copy(alpha = 0f))))
            drawPath(line, colour, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            val last = values.lastIndex
            drawCircle(colour, 5.dp.toPx(), Offset(last * stepX, y(values[last])))
            drawCircle(Color.White, 2.dp.toPx(), Offset(last * stepX, y(values[last])))
        }
    }
}

/** Slim progress bar for the monthly budget. */
@Composable
fun BudgetBar(spent: Long, budget: Long, modifier: Modifier = Modifier) {
    val money = LocalMoneyColors.current
    val ratio = if (budget > 0) spent.toFloat() / budget else 0f
    val colour = when {
        ratio >= 1f -> money.budgetOver
        ratio >= 0.8f -> money.budgetWarn
        else -> MaterialTheme.colorScheme.primary
    }
    val animated by animateFloatAsState(ratio.coerceIn(0f, 1f), tween(800), label = "budget")
    Box(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth(animated).height(10.dp).clip(CircleShape).background(colour))
    }
}

// ---------- Category picker ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (CategoryEntity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text("Choose category", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(categories, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryBadge(c.icon, c.colour.toColor(), 40.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (c.id == selectedId) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
                HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
