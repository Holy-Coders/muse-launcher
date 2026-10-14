package dev.puck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MuseSettings(model: PuckViewModel, startPairing: () -> Unit) {
    val status by model.muse.setupStatus.collectAsStateWithLifecycle()
    val pairing by model.muse.pairingOpen.collectAsStateWithLifecycle()
    val usingMuse by model.usingMuse.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }
    Text(if (usingMuse) "Muse is selected. Requests go to your Muse account; actions require local review." else "FakeAgent · offline demo. Choose Muse after pairing this phone.")
    Text(status)
    Button(onClick = { showSetup = !showSetup }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (showSetup) "Hide Muse setup" else "Muse connection setup") }
    if (showSetup) {
        Text("Pair Muse Launcher as ${model.muse.vault.bleName}. Get your personal SDK token at gadgets.muse.ai/settings/sdk-tokens. Token terms apply. Use Muse on another phone to discover this Jelly Star.")
        OutlinedTextField(value = token, onValueChange = { token = it.take(200) }, label = { Text("Muse SDK token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.muse.saveSdk(token); token = "" }, enabled = token.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save SDK token securely") }
        Text("Pairing temporarily changes this phone’s Bluetooth name, opens a ten-minute visible setup window, then restores the name. No microphone capture.")
        Button(onClick = if (pairing) model.muse::stopPairing else startPairing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (pairing) "Stop Muse pairing" else "Start Muse pairing") }
        Button(onClick = { model.useMuse(true) }, enabled = !pairing && model.muse.vault.hasCredentials(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use Muse") }
        Button(onClick = { model.muse.reconnect() }, enabled = usingMuse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reconnect Muse") }
        OutlinedButton(onClick = { model.useMuse(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use offline FakeAgent") }
        OutlinedButton(onClick = model::forgetMuse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Forget Muse on this phone") }
    }
}
