package ai.openclaw.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { App(this) }
  }
}

private object TermuxBridgeClient {
  private const val base = "http://127.0.0.1:8765"
  suspend fun health(): String = get("/health")
  suspend fun installAllStart(): String = post("/install/all/start", "{}")
  suspend fun progress(): JSONObject = JSONObject(get("/progress"))
  suspend fun logs(offset: Long): JSONObject = JSONObject(get("/logs?offset=$offset"))

  private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
    val conn = URL(base + path).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 4000
    conn.readTimeout = 6000
    conn.inputStream.bufferedReader().use(BufferedReader::readText)
  }

  private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
    val conn = URL(base + path).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.connectTimeout = 8000
    conn.readTimeout = 15000
    conn.doOutput = true
    OutputStreamWriter(conn.outputStream).use { it.write(body) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    stream.bufferedReader().use(BufferedReader::readText)
  }
}

private enum class SetupStep { DOWNLOAD_TERMUX, CREATE_BRIDGE, INSTALL_STACK, DONE }

private fun isInstalled(context: Context, pkg: String): Boolean {
  val pm = context.packageManager
  if (pm.getLaunchIntentForPackage(pkg) != null) return true
  return try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
}

private fun openTermuxInstallPage(context: Context) {
  val links = listOf(
    "https://f-droid.org/packages/com.termux/",
    "market://details?id=org.fdroid.fdroid",
    "https://f-droid.org/",
    "https://github.com/termux/termux-app/releases"
  )
  for (url in links) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
      return
    } catch (_: Exception) {}
  }
}

private fun bridgeBootstrapCommand(): String = """
# OpenClaw Mobile bootstrap (Termux)
# - enables external execution for future one-tap runs
# - installs minimal deps
# - downloads repo snapshot (no git credentials)
# - starts the bridge server (logs persisted to ~/openclaw-mobile/termux/install.log)

set -e

# 0) Enable external app execution in Termux (required for RUN_COMMAND).
#    This takes effect after settings reload.
mkdir -p "${'$'}HOME/.termux"
echo "allow-external-apps=true" >> "${'$'}HOME/.termux/termux.properties"
termux-reload-settings >/dev/null 2>&1 || true

# Ensure we have a known-good mirror configured (avoids interactive termux-change-repo)
mkdir -p "${'$'}PREFIX/etc/apt"
echo "deb https://packages-cf.termux.dev/apt/termux-main/ stable main" > "${'$'}PREFIX/etc/apt/sources.list"

# Bring the base system fully up to date (fixes curl/OpenSSL/QUIC symbol issues)
apt update -y
apt full-upgrade -y

pkg update -y
pkg install -y python curl tar termux-tools ca-certificates openssl
pkg reinstall -y openssl curl libcurl || true

# Fetch repo without git credentials/prompts (works for public repos)
rm -rf "${'$'}HOME/openclaw-mobile" || true
mkdir -p "${'$'}HOME"

curl -L "https://github.com/clawdbot-Niko/Openclaw-mobile/archive/refs/heads/main.tar.gz" \
  | tar -xz -C "${'$'}HOME"

mv "${'$'}HOME/Openclaw-mobile-main" "${'$'}HOME/openclaw-mobile"

cd "${'$'}HOME/openclaw-mobile/termux"

pkill -f bridge_server.py >/dev/null 2>&1 || true
nohup python bridge_server.py >/dev/null 2>&1 &

echo BRIDGE_STARTED
""".trimIndent()

private fun openAppSettings(context: Context, packageName: String) {
  try {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:$packageName")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
  } catch (_: Exception) {}
}

private fun launchBridgeBootstrapInTermux(context: Context): String {
  val bootstrap = bridgeBootstrapCommand()
  val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
    ?: return "No se encontró Termux. Instálalo primero."

  // Always copy the bootstrap so we can fall back to manual paste.
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("termux_bridge_bootstrap", bootstrap))

  return try {
    // 1) Open Termux
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // 2) Auto-run command via RUN_COMMAND (may fail if Termux blocks external execution)
    val runIntent = Intent("com.termux.app.RUN_COMMAND").apply {
      // Explicit service intent for Android 8+ reliability.
      setClassName("com.termux", "com.termux.app.RunCommandService")
      putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
      putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", bootstrap))
      putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
      putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
    }
    context.startService(runIntent)

    // 3) Return to app automatically
    Handler(Looper.getMainLooper()).postDelayed({
      val back = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      context.startActivity(back)
    }, 1800)

    "Ejecutando bridge automáticamente..."
  } catch (_: Exception) {
    // Manual fallback: user pastes from clipboard.
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "FALLBACK_MANUAL_PASTE\n\nNo pude ejecutar el comando automáticamente en Termux (permiso/ajuste).\n\nYa copié el comando al portapapeles.\n\n1) Se abrirá Termux\n2) Deja presionado dentro de la terminal\n3) Toca PEGAR\n4) Presiona ENTER\n5) Regresa a la app y toca 'Revisar estado'"
  }
}

@Composable
fun App(context: Context) {
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf("Revisando estado...") }
  var step by remember { mutableStateOf(SetupStep.DOWNLOAD_TERMUX) }
  var pBridge by remember { mutableFloatStateOf(0f) }
  var pUbuntu by remember { mutableFloatStateOf(0f) }
  var pOpenclaw by remember { mutableFloatStateOf(0f) }
  var showRestartBridge by remember { mutableStateOf(false) }
  var showPermissionFix by remember { mutableStateOf(false) }
  var logsText by remember { mutableStateOf("(sin logs aún)") }
  val logsScroll = rememberScrollState()

  fun buttonLabel(s: SetupStep) = when (s) {
    SetupStep.DOWNLOAD_TERMUX -> "1) Descargar Termux"
    SetupStep.CREATE_BRIDGE -> "2) Crear bridge con Termux"
    SetupStep.INSTALL_STACK -> "3) Instalar Ubuntu + OpenClaw"
    SetupStep.DONE -> "✅ Listo"
  }

  fun refreshStep() {
    scope.launch {
      if (!isInstalled(context, "com.termux")) {
        step = SetupStep.DOWNLOAD_TERMUX
        status = "No se detecta Termux"
        pBridge = 0f
        showRestartBridge = false
        showPermissionFix = false
        return@launch
      }
      val up = try { TermuxBridgeClient.health(); true } catch (_: Exception) { false }
      if (!up) {
        step = SetupStep.CREATE_BRIDGE
        status = "Termux detectado, bridge no activo"
        pBridge = 0.35f
        showRestartBridge = true
        return@launch
      }
      pBridge = 1f
      step = SetupStep.INSTALL_STACK
      status = "Bridge activo ✅"
      showRestartBridge = false
      showPermissionFix = false
    }
  }

  LaunchedEffect(Unit) { refreshStep() }

  var logOffset by remember { mutableStateOf(0L) }

  LaunchedEffect(Unit) {
    while (true) {
      try {
        val j = TermuxBridgeClient.logs(logOffset)
        val text = j.optString("text", "")
        val next = j.optLong("nextOffset", logOffset)
        if (text.isNotEmpty()) {
          logsText = (logsText + text).takeLast(2_000_000) // keep UI bounded; full log is on device
        }
        logOffset = next
      } catch (_: Exception) {
        // ignore
      }
      delay(1500)
    }
  }

  val pageScroll = rememberScrollState()

  MaterialTheme {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .verticalScroll(pageScroll),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Image(
        painter = painterResource(id = R.drawable.lobsterd_logo),
        contentDescription = "logo",
        modifier = Modifier.fillMaxWidth().height(170.dp),
        contentScale = ContentScale.Crop
      )
      Text("OpenClaw Mobile", style = MaterialTheme.typography.headlineMedium)

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Estado: $status")
          Text("Paso actual: ${buttonLabel(step)}")

          Text("Bridge ${(pBridge * 100).toInt()}%")
          LinearProgressIndicator(progress = { pBridge }, modifier = Modifier.fillMaxWidth())

          Text("Ubuntu ${(pUbuntu * 100).toInt()}%")
          LinearProgressIndicator(progress = { pUbuntu }, modifier = Modifier.fillMaxWidth())

          Text("OpenClaw ${(pOpenclaw * 100).toInt()}%")
          LinearProgressIndicator(progress = { pOpenclaw }, modifier = Modifier.fillMaxWidth())
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Logs en tiempo real")
          Text(
            logsText.ifBlank { "(sin logs)" },
            modifier = Modifier
              .fillMaxWidth()
              .height(180.dp)
              .verticalScroll(logsScroll)
          )
          Button(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("openclaw_logs", logsText))
            status = "Logs copiados"
          }, modifier = Modifier.fillMaxWidth()) {
            Text("Copiar logs")
          }
        }
      }

      Button(onClick = {
        when (step) {
          SetupStep.DOWNLOAD_TERMUX -> {
            openTermuxInstallPage(context)
            status = "Te abrí F-Droid/Termux"
          }
          SetupStep.CREATE_BRIDGE -> {
            showRestartBridge = false
            pBridge = 0f; pUbuntu = 0f; pOpenclaw = 0f
            logsText = "(reiniciando bridge...)"
            status = launchBridgeBootstrapInTermux(context)
            showPermissionFix = status.startsWith("FALLBACK_MANUAL_PASTE")
            scope.launch {
              repeat(20) { i ->
                delay(2500)
                pBridge = (i + 1) / 20f
                val up = try { TermuxBridgeClient.health(); true } catch (_: Exception) { false }
                if (up) {
                  pBridge = 1f
                  step = SetupStep.INSTALL_STACK
                  status = "Bridge activo ✅"
                  showPermissionFix = false
                  return@launch
                }
              }
              status = "Bridge no activo aún. Reabre Termux y reintenta."
              showRestartBridge = true
            }
          }
          SetupStep.INSTALL_STACK -> {
            scope.launch {
              try {
                TermuxBridgeClient.installAllStart()
                repeat(180) {
                  delay(2000)
                  val p = TermuxBridgeClient.progress()
                  pBridge = (p.optJSONObject("bridge")?.optInt("percent", 0) ?: 0) / 100f
                  pUbuntu = (p.optJSONObject("ubuntu")?.optInt("percent", 0) ?: 0) / 100f
                  pOpenclaw = (p.optJSONObject("openclaw")?.optInt("percent", 0) ?: 0) / 100f
                  val phase = p.optString("phase", "")
                  val detail = p.optString("detail", "")
                  status = "$phase ${if (detail.isNotBlank()) "- $detail" else ""}"

                  if (!p.optBoolean("running", false) && phase == "done") {
                    step = SetupStep.DONE
                    status = "Instalación completa ✅"
                    return@launch
                  }
                  if (!p.optBoolean("running", false) && phase == "error") {
                    step = SetupStep.CREATE_BRIDGE
                    status = "Error instalación: $detail (recrea bridge)"
                    showRestartBridge = true
                    return@launch
                  }
                }
                status = "Timeout leyendo progreso"
                showRestartBridge = true
              } catch (e: Exception) {
                step = SetupStep.CREATE_BRIDGE
                status = "Error instalación: ${e.message}"
                showRestartBridge = true
              }
            }
          }
          SetupStep.DONE -> status = "Todo listo ✅"
        }
      }, modifier = Modifier.fillMaxWidth()) {
        Text(buttonLabel(step))
      }

      if (step != SetupStep.DOWNLOAD_TERMUX) {
        Button(onClick = {
          pBridge = 0f; pUbuntu = 0f; pOpenclaw = 0f
          logsText = "(reiniciando bridge...)"
          status = launchBridgeBootstrapInTermux(context)
          showPermissionFix = status.startsWith("FALLBACK_MANUAL_PASTE")
          step = SetupStep.CREATE_BRIDGE
          showRestartBridge = false
        }, modifier = Modifier.fillMaxWidth()) {
          Text("Reiniciar bridge")
        }
      }

      if (showPermissionFix) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Acción manual requerida", style = MaterialTheme.typography.titleMedium)
            Text(
              "Ya copié el comando al portapapeles.\n\n" +
                "1) Abrir Termux\n" +
                "2) Deja presionado dentro de la terminal\n" +
                "3) Toca PEGAR\n" +
                "4) Presiona ENTER\n" +
                "5) Regresa a la app y toca 'Revisar estado'"
            )
            Button(onClick = {
              val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
              if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                status = "Termux abierto. Pega el comando y presiona Enter."
              } else {
                status = "No se encontró Termux. Instálalo primero."
              }
            }, modifier = Modifier.fillMaxWidth()) {
              Text("Abrir Termux para pegar")
            }
          }
        }
      }

      Button(onClick = { refreshStep() }, modifier = Modifier.fillMaxWidth()) {
        Text("Revisar estado")
      }
    }
  }
}
