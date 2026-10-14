package app.spendtracker.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.MerchantRuleEntity
import app.spendtracker.data.repository.CategoryRepository
import app.spendtracker.domain.model.CategoryType
import app.spendtracker.ui.components.COLOUR_CHOICES
import app.spendtracker.ui.components.CategoryBadge
import app.spendtracker.ui.components.ICON_CHOICES
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.components.categoryIcon
import app.spendtracker.ui.components.toColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val repo: CategoryRepository) : ViewModel() {
    val categories: StateFlow<List<CategoryEntity>> = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val userRules: StateFlow<List<MerchantRuleEntity>> = repo.observeUserRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(existing: CategoryEntity?, name: String, colour: Long, icon: String) = viewModelScope.launch {
        if (existing == null) repo.add(name, colour, icon, CategoryType.EXPENSE)
        else repo.update(existing.copy(name = name.trim().ifBlank { existing.name }, colour = colour, icon = icon))
    }

    fun merge(from: Long, into: Long) = viewModelScope.launch { repo.merge(from, into) }
    fun deleteRule(id: Long) = viewModelScope.launch { repo.deleteUserRule(id) }
}

private val PROTECTED = setOf("uncategorised", "transfers", "income")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val rules by vm.userRules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Rounded.Add, "Add category") }
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    cats.forEachIndexed { i, c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = c }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryBadge(c.icon, c.colour.toColor(), 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (c.slug !in PROTECTED) TextButton(onClick = { merging = c }) { Text("Merge") }
                        }
                        if (i < cats.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
            if (rules.isNotEmpty()) {
                item { SectionTitle("Learned from your corrections", Modifier.padding(start = 4.dp)) }
                item {
                    SectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                        rules.forEach { r ->
                            val cat = cats.firstOrNull { it.id == r.categoryId }
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.pattern.replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodyLarge)
                                    Text("→ ${cat?.name ?: "?"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Rounded.Close, "Forget rule") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(64.dp)) }
        }
    }

    if (creating || editing != null) {
        EditCategoryDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, colour, icon -> vm.save(editing, name, colour, icon); creating = false; editing = null },
        )
    }
    merging?.let { from ->
        MergeDialog(from, cats.filter { it.id != from.id }, onDismiss = { merging = null }, onMerge = { into -> vm.merge(from.id, into.id); merging = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditCategoryDialog(existing: CategoryEntity?, onDismiss: () -> Unit, onSave: (String, Long, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var colour by remember { mutableLongStateOf(existing?.colour ?: COLOUR_CHOICES.first()) }
    var icon by remember { mutableStateOf(existing?.icon ?: "shopping") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    COLOUR_CHOICES.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(c.toColor())
                                .border(if (c == colour) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { colour = c },
                        )
                    }
                }
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ICON_CHOICES.forEach { ic ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (ic == icon) colour.toColor().copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { icon = ic },
                            contentAlignment = Alignment.Center,
                        ) { Icon(categoryIcon(ic), null, tint = colour.toColor(), modifier = Modifier.size(20.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, colour, icon) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MergeDialog(from: CategoryEntity, targets: List<CategoryEntity>, onDismiss: () -> Unit, onMerge: (CategoryEntity) -> Unit) {
    var target by remember { mutableStateOf<CategoryEntity?>(null) }
    var open by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge ${from.name}") },
        text = {
            Column {
                Text("All transactions and rules in ${from.name} move to the category you pick, then ${from.name} is removed.")
                Spacer(Modifier.height(12.dp))
                Box {
                    OutlinedButton(onClick = { open = true }) { Text(target?.name ?: "Choose category") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        targets.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { target = t; open = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { target?.let(onMerge) }, enabled = target != null) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
