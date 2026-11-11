package ai.hindidub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * API configuration.
 *
 * Keys are written to the Android Keystore (never to prefs), the router only uses providers that
 * have a key, and every stage falls back to the local engine if a cloud call fails. The plan card
 * shows exactly what the next job will use, so "hybrid mode" is not a mystery.
 */
@Composable
fun ApiScreen(vm: AppViewModel, state: UiState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Cloud APIs", "Optional. The app works fully offline without any key.")

        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(ai.hindidub.R.drawable.ic_cloud), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Hybrid mode", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.settings.cloudEnabled,
                        onCheckedChange = { vm.setCloudEnabled(it) }
                    )
                }
                Text(
                    "When on and online, translation runs through your chosen provider and falls " +
                        "back to the on-device model if a request fails. Video and audio never leave " +
                        "the phone unless you also enable cloud ASR.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!state.networkOnline) {
                    Text(
                        "No network right now — every stage will run locally.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HorizontalDivider()
                Text("Next job", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.planSummary, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
            }
        }

        vm.providers().forEach { p ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(ai.hindidub.R.drawable.ic_key), null,
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                p.endpoint.removePrefix("https://").substringBefore('/'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.settings.providerOrder
                                .split(',').map { it.trim() }.contains(p.id),
                            onCheckedChange = { vm.setProviderEnabled(p.id, it) }
                        )
                    }
                    ProviderKeyRow(vm, p.id, state.keyPresent[p.id] == true)
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud voice", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Optional. Uses OpenAI speech, which returns WAV, so the app decodes it with no " +
                        "extra codec. Google Cloud TTS is not offered: it needs an OAuth2 service " +
                        "account rather than an API key, and pasting a service-account file into a " +
                        "phone app is not a trade worth making.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.settings.cloudTtsProvider.isEmpty(),
                        onClick = { vm.setCloudTtsProvider("") },
                        label = { Text("On-device voice") }
                    )
                    FilterChip(
                        selected = state.settings.cloudTtsProvider == "openai",
                        onClick = { vm.setCloudTtsProvider("openai") },
                        label = { Text("OpenAI voice") }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProviderKeyRow(vm: AppViewModel, id: String, present: Boolean) {
    var key by remember(id) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (present) "Key saved — paste to replace" else "API key") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = { vm.saveKey(id, key); key = "" },
            enabled = key.isNotBlank() || present
        ) { Text(if (key.isBlank() && present) "Remove" else "Save") }
    }
    if (present) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(ai.hindidub.R.drawable.ic_check), null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text("key stored in the Android Keystore",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
