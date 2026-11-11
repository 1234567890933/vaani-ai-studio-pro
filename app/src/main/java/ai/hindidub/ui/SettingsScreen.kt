package ai.hindidub.ui

import ai.hindidub.core.LanguageCatalog
import ai.hindidub.core.StorageManager
import ai.hindidub.model.ModelGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Settings, grouped by what the user is trying to do rather than by which class reads the value.
 * Every control writes through the ViewModel, which persists to prefs on close — no per-frame
 * disk writes while a slider is being dragged.
 */
@Composable
fun SettingsScreen(vm: AppViewModel, state: UiState) {
    var picker by remember { mutableStateOf<String?>(null) }
    var showCrash by remember { mutableStateOf(false) }
    val s = state.settings

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Settings", state.deviceLine)

        Section("Language") {
            RowSetting(
                "Spoken in the video",
                LanguageCatalog.byCode(s.sourceLang)?.englishName ?: s.sourceLang
            ) { picker = "src" }
            RowSetting(
                "Dub into",
                LanguageCatalog.targetByCode(s.targetLang)?.nativeName ?: s.targetLang
            ) { picker = "tgt" }
            Text(
                "Voice locale: ${LanguageCatalog.targetByCode(s.targetLang)?.tts ?: "—"}" +
                    if (state.voiceReady) " · installed" else " · not installed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Voice and sound") {
            ChipRow(
                vm.voiceProfiles().map { it.id to it.label },
                s.voiceProfileId
            ) { vm.setVoiceProfile(it) }
            SliderSetting(
                "Original bed under the dub",
                "${s.bedGainDb.toInt()} dB",
                s.bedGainDb, -40f..0f
            ) { vm.setBedGain(it) }
            SwitchSetting(
                "Reduce original dialogue",
                "Subtracts the centre channel; keeps music and ambience",
                s.suppressCenter
            ) { vm.setSuppressCenter(it) }
        }

        Section("Output quality") {
            ChipRow(
                listOf(24000 to "24 kHz", 32000 to "32 kHz", 44100 to "44.1 kHz"),
                s.mixSampleRate.toString()
            ) { vm.setSampleRate(it.toInt()) }
            ChipRow(
                listOf(64 to "64 kbps", 96 to "96 kbps", 128 to "128 kbps"),
                s.aacKbps.toString()
            ) { vm.setBitrate(it.toInt()) }
            Text(
                "Higher rates cost more disk and encoding time; 24 kHz is what the system voice produces.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Performance") {
            SliderSetting(
                "CPU threads",
                "${s.threads}",
                s.threads.toFloat(), 1f..8f
            ) { vm.setThreads(it.toInt().coerceIn(1, 8)) }
            SliderSetting(
                "Model storage quota",
                "${s.quotaGb} GB",
                s.quotaGb.toFloat(), 2f..22f
            ) { vm.setQuotaGb(it.toInt().coerceIn(2, 22)) }
            SwitchSetting(
                "Quick mode",
                "Dub only the first 60 seconds — fastest way to test a setting",
                s.quickSeconds > 0
            ) { vm.setQuickMode(it) }
            Text(
                "The thermal governor lowers threads automatically when the phone gets hot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Models") {
            Text(state.poolSummary, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) {
                AnimatedStageText(state.busyLabel, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                AnimatedProgressBar(state.busyFraction)
            }
            Button(
                onClick = { vm.downloadEverything() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painterResource(ai.hindidub.R.drawable.ic_download), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download all packages")
            }
            vm.asrGroups().forEach { g -> ModelRow(g, vm, s.asrGroupId) }
            vm.mtGroups().forEach { g -> ModelRow(g, vm, s.mtGroupId) }
        }

        Section("Translation") {
            ChipRow(listOf("offline" to "On-device", "cloud" to "Cloud"), s.translationMode) {
                vm.setTranslationMode(it)
            }
            if (s.translationMode == "cloud") {
                ChipRow(
                    listOf("single" to "single", "failover" to "failover", "parallel" to "parallel"),
                    s.routingMode
                ) { vm.setRoutingMode(it) }
                vm.providers().forEach { p ->
                    ProviderRow(vm, p.id, p.label, p.endpoint, s.providerOrder)
                }
                state.providerStatus.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Section("Appearance") {
            ChipRow(
                listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                s.themeMode
            ) { vm.setTheme(it) }
        }

        Section("Storage and diagnostics") {
            Text(
                "Library: ${state.outputs.size} file(s), ${StorageManager.human(state.storageTotal)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshStorage() }) { Text("Rescan") }
                OutlinedButton(onClick = { showCrash = true }) { Text("Last crash log") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    picker?.let { which ->
        LanguageDialog(
            title = if (which == "src") "Spoken language" else "Dub into",
            options = if (which == "src") vm.languages() else vm.targetLanguages(),
            selected = if (which == "src") s.sourceLang else s.targetLang,
            onPick = { if (which == "src") vm.setSourceLang(it) else vm.setTargetLang(it) },
            onDismiss = { picker = null }
        )
    }

    if (showCrash) {
        val log = remember { vm.crashLog() }
        AlertDialog(
            onDismissRequest = { showCrash = false },
            confirmButton = {
                TextButton(onClick = { vm.clearCrashLog(); showCrash = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showCrash = false }) { Text("Close") } },
            title = { Text("Last crash") },
            text = {
                Text(
                    log ?: "No crash recorded.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        )
    }
}

// ---- building blocks -------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RowSetting(label: String, value: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(value) }
    }
}

@Composable
private fun SwitchSetting(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ChipRow(options: List<Pair<*, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val key = value.toString()
            FilterChip(
                selected = key == selected,
                onClick = { onPick(key) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun ModelRow(g: ModelGroup, vm: AppViewModel, selectedId: String) {
    val installed = vm.isInstalled(g)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                g.label, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${g.totalMb} MB · ${if (installed) "installed" else "not installed"} · ${g.license}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (g.id == selectedId) {
            Text("in use", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = { vm.useGroup(g) }, enabled = installed) { Text("Use") }
        }
        if (!installed) {
            IconButton(onClick = { vm.download(g) }) {
                Icon(painterResource(ai.hindidub.R.drawable.ic_download), "Download",
                    Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProviderRow(
    vm: AppViewModel,
    id: String,
    label: String,
    endpoint: String,
    order: String
) {
    val enabled = order.split(',').map { it.trim() }.contains(id)
    var key by remember(id) { mutableStateOf(vm.keyFor(id)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    endpoint.removePrefix("https://").substringBefore('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = { vm.setProviderEnabled(id, it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.saveKey(id, key) }) { Text("Save") }
        }
    }
}
