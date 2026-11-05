package com.eberhard.rokidmirror.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        val prefs = getSharedPreferences("mirror", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8AB4F8))) {
                Screen(prefs.getString("host", "").orEmpty()) { prefs.edit().putString("host", it).apply() }
            }
        }
    }
}

@Composable
private fun Screen(savedHost: String, rememberHost: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by MirrorService.state.collectAsStateWithLifecycle()
    val discovery = remember { Discovery(context) }
    val found by discovery.found.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf(savedHost) }
    var dexMode by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    // The glasses found on the network fill the box when it's empty.
    if (host.isBlank() && found.isNotEmpty()) host = found.first().host

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            rememberHost(host.trim())
            MirrorService.start(context, result.resultCode, data, host.trim())
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun begin() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection.launch(manager.createScreenCaptureIntent())
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Rokid Mirror", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Mirror", !dexMode) { dexMode = false }
            ModeChip("DeX", dexMode) { dexMode = true }
        }
        if (dexMode) {
            DexPanel(context)
            return@Column
        }
        Text(
            "Shows this phone's screen in the Rokid glasses. Open Rokid Screen on the glasses first; " +
                "both must be on the same Wi-Fi or on this phone's hotspot.",
            color = Color(0xFFB0B0B8), fontSize = 14.sp, lineHeight = 20.sp,
        )

        Text("Glasses", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        if (found.isEmpty()) {
            Text("Looking for Rokid Screen on the network… If it isn't found, type the IP the glasses show.", color = Color(0xFFB0B0B8), fontSize = 13.sp)
        } else {
            found.forEach { f ->
                OutlinedButton(onClick = { host = f.host }, modifier = Modifier.fillMaxWidth()) {
                    Text("${f.name} · ${f.host}" + if (f.host == host) "  ✓" else "")
                }
            }
        }
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true,
            label = { Text("Glasses IP address") }, modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            MirrorService.State.Off -> Button(onClick = ::begin, enabled = host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Start mirroring")
            }
            is MirrorService.State.Connecting -> Text("Connecting to ${s.host}…")
            is MirrorService.State.Streaming -> {
                Text("Mirroring to ${s.host} at ${s.width}×${s.height}", color = Color(0xFF80E27E))
                Button(onClick = { MirrorService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            }
            is MirrorService.State.Failed -> {
                Text(s.message, color = Color(0xFFFF8080))
                Button(onClick = ::begin, enabled = host.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
        val log by Log.recent.collectAsStateWithLifecycle()
        if (log.isNotEmpty()) {
            Text("Log", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            log.forEach { Text(it, color = Color(0xFFB0B0B8), fontSize = 11.sp, lineHeight = 15.sp) }
            Text("Saved to Download/RokidMirror when a run ends.", color = Color(0xFF8A8A92), fontSize = 11.sp)
        }
        Text(
            "The picture is scaled to the glasses' 480×640 display and shown in green. Sound stays on the phone " +
                "(or in the glasses as a Bluetooth headset). Folding or unfolding re-fits the picture.",
            color = Color(0xFF8A8A92), fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}


@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

/**
 * DeX mode: a second, virtual screen on this phone that Samsung DeX runs on (One UI 8+),
 * streamed to the glasses. The work is done by "dexd", a helper that lives on the phone with
 * shell rights: armed once per boot through Wireless debugging (tools/rokid-dex.sh arm, run
 * via Termux), after which Start/Stop here talk to it directly, on any network.
 */
@Composable
private fun DexPanel(context: Context) {
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var port by rememberSaveable { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    suspend fun dexd(command: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", Protocol.DEXD_CONTROL_PORT), 1500)
                s.getOutputStream().write((command + "\n").toByteArray())
                s.getOutputStream().flush()
                s.getInputStream().bufferedReader().readLine()
            }
        }.getOrNull()
    }
    fun refresh() { scope.launch { status = dexd("status") } }
    LaunchedEffect(Unit) { refresh() }

    val askRun = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        note = if (ok) "Termux may now run the script. Tap Arm again." else "Without that permission, arm from Termux: ~/bin/rokid-dex.sh arm <port>"
    }
    fun arm() {
        val perm = "com.termux.permission.RUN_COMMAND"
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askRun.launch(perm)
            return
        }
        val args = if (port.isBlank()) arrayOf("arm") else arrayOf("arm", port.trim())
        val i = android.content.Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/bin/rokid-dex.sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        note = runCatching { context.startForegroundService(i); "Arming through Termux… (takes ~5 s)" }
            .getOrElse { "Termux refused: ${it.message}. In Termux: ~/bin/rokid-dex.sh arm ${port.trim()}" }
        scope.launch { kotlinx.coroutines.delay(6000); refresh() }
    }

    val armed = status != null
    Text(
        "A second screen for Samsung DeX, shown in the glasses while this phone keeps its own screen. " +
            "A Bluetooth mouse and keyboard paired to the phone drive it. Works on any Wi-Fi or this phone's hotspot.",
        color = Color(0xFFB0B0B8), fontSize = 14.sp, lineHeight = 20.sp,
    )
    Text(
        if (armed) "Helper: ${status}" else "Helper not armed since the last reboot.",
        color = if (armed) Color(0xFF80E27E) else Color(0xFFFFB380), fontSize = 13.sp,
    )
    if (!armed) {
        Text(
            "Once per boot, while on Wi-Fi: Developer options → Wireless debugging → on, then enter the port shown there and tap Arm.",
            color = Color(0xFF8A8A92), fontSize = 12.sp, lineHeight = 17.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) }, singleLine = true,
                label = { Text("Wireless debugging port") }, modifier = Modifier.weight(1f),
            )
            Button(onClick = ::arm, modifier = Modifier.padding(top = 8.dp)) { Text("Arm") }
        }
    }
    Button(
        enabled = armed,
        onClick = { scope.launch { note = dexd("start") ?: "Helper not answering"; refresh() } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Start DeX screen") }
    OutlinedButton(
        enabled = armed,
        onClick = { scope.launch { note = dexd("stop") ?: "Helper not answering"; refresh() } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Stop DeX screen") }
    OutlinedButton(onClick = ::refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }
    if (note.isNotEmpty()) Text(note, color = Color(0xFFB0B0B8), fontSize = 13.sp)
    Text(
        "Rokid Screen on the glasses finds the DeX screen by itself. Manual: ~/bin/rokid-dex.sh arm <port> / start / stop.",
        color = Color(0xFF8A8A92), fontSize = 12.sp, lineHeight = 17.sp,
    )
}
