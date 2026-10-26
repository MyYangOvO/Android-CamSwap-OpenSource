package io.github.zensu357.camswap.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.zensu357.camswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

/**
 * Lets the user tick / untick the apps that receive the replaced camera picture.
 * Changes are saved immediately and take effect live inside the hooked apps.
 */
@Composable
fun AppSelectScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadConfig()
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map {
                    AppEntry(
                        label = runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName),
                        packageName = it.packageName,
                        isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    )
                }
        }
        loading = false
    }

    val selected = uiState.targetPackages
    val visible = remember(apps, query, showSystem, selected) {
        val q = query.trim().lowercase(Locale.getDefault())
        apps.asSequence()
            // Always keep ticked apps visible, even if they are system apps.
            .filter { showSystem || !it.isSystem || it.packageName in selected }
            .filter {
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(q)
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.packageName in selected }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
            .toList()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.apps_filter_enable), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                if (uiState.targetFilterEnabled) R.string.apps_filter_enable_desc_on
                                else R.string.apps_filter_enable_desc_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = uiState.targetFilterEnabled,
                        onCheckedChange = { viewModel.setTargetFilterEnabled(it) }
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.apps_scope_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.apps_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.clickable { showSystem = !showSystem },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                Text(stringResource(R.string.apps_show_system), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.apps_selected_count, selected.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                viewModel.updateTargetPackages(add = visible.map { it.packageName }, remove = emptyList())
            }) { Text(stringResource(R.string.apps_select_visible)) }
            TextButton(onClick = {
                viewModel.updateTargetPackages(add = emptyList(), remove = visible.map { it.packageName })
            }) { Text(stringResource(R.string.apps_clear_visible)) }
        }

        @Suppress("DEPRECATION")
        Divider()

        if (loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setTargetPackage(app.packageName, !checked) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = remember(app.packageName) {
                            runCatching {
                                context.packageManager.getApplicationIcon(app.packageName)
                                    .toBitmap(96, 96).asImageBitmap()
                            }.getOrNull()
                        }
                        if (icon != null) {
                            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { viewModel.setTargetPackage(app.packageName, it) }
                        )
                    }
                }
            }
        }
    }
}
