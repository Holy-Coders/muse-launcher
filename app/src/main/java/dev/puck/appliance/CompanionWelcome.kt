package dev.puck.appliance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.puck.MuseRuntime
import dev.puck.core.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** User-owned credentials, optional Android roles and a correlated connection check. */
@Composable
fun CompanionWelcome(runtime:MuseRuntime,open:(String)->Unit,pair:()->Unit,permissions:()->Unit,home:()->Unit,done:()->Unit) {
    val status by runtime.setupStatus.collectAsState()
    val connection by runtime.connection.collectAsState()
    val pairing by runtime.pairingOpen.collectAsState()
    val scope=rememberCoroutineScope()
    var token by remember {mutableStateOf("")}
    var terms by remember {mutableStateOf(false)}
    var checking by remember {mutableStateOf(false)}
    var verified by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf("")}
    Text("Meet your Muse",style=MaterialTheme.typography.headlineMedium)
    Text("Turn a spare Android phone into a companion. Hold the screen button to talk, take photos or videos, and read replies here.")
    Text("1 · Connect your own account",style=MaterialTheme.typography.titleMedium)
    Text("You need Muse signed in on a second phone for Bluetooth pairing. This phone needs internet and Bluetooth. No root or Wi-Fi password is required.")
    TextButton(onClick={open("https://gadgets.muse.ai/settings/sdk-tokens")}){Text("Get my Muse SDK token")}
    TextButton(onClick={open("https://gadgets.muse.ai/sdk-terms")}){Text("Read Muse token terms")}
    Text("Tokens are for personal, non-commercial use. Muse Launcher is an independent community project, not endorsed by Meta.",style=MaterialTheme.typography.bodySmall)
    Row {
        Checkbox(terms,{terms=it})
        Text("I reviewed the terms and will use my own token.",Modifier.padding(top=12.dp))
    }
    if(!runtime.vault.hasSdkToken()) {
        OutlinedTextField(token,{token=it.take(200)},label={Text("Personal SDK token")},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
        Button(enabled=terms && token.isNotBlank(),onClick={runtime.saveSdk(token);token=""}){Text("Save securely on this phone")}
    }
    Text("Voice recordings, attached photos/videos and your requests go to your Muse account. Replies and saved media stay in Muse Launcher's private storage. No developer server or analytics receives them. Notification previews stay on this phone.",style=MaterialTheme.typography.bodySmall)
    Text(status)
    if(runtime.vault.hasSdkToken() && !runtime.vault.hasCredentials()) {
        Button(onClick=if(pairing)runtime::stopPairing else pair){Text(if(pairing)"Stop pairing" else "Start Bluetooth pairing")}
        Text("On your other phone: Muse → Settings → Devices → Developer mode → Add a device. Choose ${runtime.vault.bleName}, then Current phone connection.")
    }
    if(runtime.vault.hasCredentials()) {
        Button(onClick={runtime.vault.enabled=true;runtime.reconnect()}){Text(if(connection==ConnectionStatus.MuseConnected)"Reconnect Muse" else "Connect Muse")}
        Button(enabled=connection==ConnectionStatus.MuseConnected && !checking,onClick={
            checking=true;verified=false;result="Checking…"
            scope.launch {
                try {
                    val reply=withContext(Dispatchers.IO){runtime.send(UUID.randomUUID().toString(),"Connection check. Reply exactly: Muse Launcher connection verified. Do not use tools or take actions.","native")}
                    verified=reply.optBoolean("ok") && reply.optString("text").contains("Muse Launcher connection verified",ignoreCase=true)
                    result=if(verified)"Muse replied — connected" else "No verified reply. Reconnect and try again."
                } catch(_:Exception){result="Connection check failed. Try again."}
                finally {checking=false}
            }
        }){Text("Test my connection")}
        if(result.isNotBlank())Text(result)
    }
    Text("2 · Choose optional features",style=MaterialTheme.typography.titleMedium)
    Text("Grant microphone for hold-to-talk and camera for photos/video. Notifications and making Muse Launcher your Home screen are optional; enable them later in Setup.")
    Button(onClick=permissions){Text("Microphone and camera permissions")}
    OutlinedButton(onClick=home){Text("Choose Muse Launcher as Home (optional)")}
    Text("3 · Start talking",style=MaterialTheme.typography.titleMedium)
    Text("Hold Talk, speak, then release to send. Phones with an extra hardware button can calibrate it in Setup. Locked-screen hardware activation depends on the device and is not guaranteed.")
    Button(enabled=verified,onClick=done,modifier=Modifier.fillMaxWidth()){Text("Start Muse Launcher")}
    TextButton(onClick=done){Text("Try Muse Launcher before connecting")}
}
