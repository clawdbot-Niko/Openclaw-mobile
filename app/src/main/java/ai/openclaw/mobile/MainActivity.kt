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
  suspend fun logs(): String = get("/logs")

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
pkg update -y
pkg install -y python nodejs proot-distro git
mkdir -p ~/openclaw-mobile/termux
cat > ~/openclaw-mobile/termux/bridge_server.py <<'PY'
#!/usr/bin/env python3
import json, subprocess, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {
  'running': False,
  'phase': 'idle',
  'detail': '',
  'bridge': {'percent': 100},
  'ubuntu': {'percent': 0},
  'openclaw': {'percent': 0},
  'logs': []
}

def add_log(msg):
    try:
      state['logs'].append(str(msg))
      if len(state['logs']) > 300:
        state['logs'] = state['logs'][-300:]
    except Exception:
      pass

def run(cmd, timeout=1800):
    add_log(f'$ {cmd}')
    p = subprocess.run(cmd, shell=True, text=True, capture_output=True, timeout=timeout)
    if p.stdout: add_log(p.stdout.strip())
    if p.stderr: add_log(p.stderr.strip())
    return p.returncode, (p.stdout or '').strip(), (p.stderr or '').strip()

def setp(target, pct):
    state[target]['percent'] = max(0, min(100, int(pct)))

def worker_install_all():
    state['running']=True
    state['phase']='ubuntu'
    state['detail']='Instalando Ubuntu'
    setp('ubuntu', 10)
    c,o,e = run('pkg install -y proot-distro', 1200)
    if c!=0:
      state.update({'running':False,'phase':'error','detail':e[:300]}); return
    setp('ubuntu', 35)
    c,o,e = run('proot-distro install ubuntu || true', 3600)
    setp('ubuntu', 100)

    state['phase']='openclaw'
    state['detail']='Instalando OpenClaw'
    setp('openclaw', 15)
    c,o,e = run('npm i -g openclaw', 1800)
    if c!=0:
      state.update({'running':False,'phase':'error','detail':e[:300]}); return
    setp('openclaw', 70)
    c,o,e = run('openclaw configure --mode local || true', 1200)
    setp('openclaw', 100)

    state.update({'running':False,'phase':'done','detail':'Ubuntu + OpenClaw listos'})

class H(BaseHTTPRequestHandler):
    def sendj(self, code, payload):
        d = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(d)))
        self.end_headers()
        self.wfile.write(d)

    def do_GET(self):
        if self.path == '/health': return self.sendj(200, {'ok': True, 'service': 'termux-bridge'})
        if self.path == '/progress': return self.sendj(200, state)
        if self.path == '/logs': return self.sendj(200, {'ok': True, 'logs': '\n'.join(state.get('logs', []))})
        return self.sendj(404, {'error':'not_found'})

    def do_POST(self):
        if self.path == '/install/all/start':
            if state.get('running'): return self.sendj(200, {'ok':True,'message':'already_running'})
            threading.Thread(target=worker_install_all, daemon=True).start()
            return self.sendj(200, {'ok':True,'message':'started'})
        return self.sendj(404, {'error':'not_found'})

ThreadingHTTPServer(('127.0.0.1',8765), H).serve_forever()
PY
chmod +x ~/openclaw-mobile/termux/bridge_server.py
pkill -f bridge_server.py >/dev/null 2>&1 || true
nohup python ~/openclaw-mobile/termux/bridge_server.py > ~/openclaw-mobile/termux/bridge.log 2>&1 &
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

  // Fallback manual (always keep command copied).
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("termux_bridge_bootstrap", bootstrap))

  return try {
    // 1) Open Termux
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // 2-3) Auto-run command (paste+enter equivalent via RUN_COMMAND)
    val runIntent = Intent("com.termux.app.RUN_COMMAND").apply {
      setPackage("com.termux")
      putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
      putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", bootstrap))
      putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
      putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
    }
    context.startService(runIntent)

    // 4) Return to app automatically
    Handler(Looper.getMainLooper()).postDelayed({
      val back = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      context.startActivity(back)
    }, 1800)

    "Ejecutando bridge automáticamente..."
  } catch (_: Exception) {
    openAppSettings(context, "com.termux")
    "Faltan permisos para ejecutar comandos automáticos en Termux. Actívalos en Ajustes > Termux y habilita ejecución externa (allow-external-apps), luego reintenta."
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

  LaunchedEffect(step) {
    while (true) {
      try {
        val j = JSONObject(TermuxBridgeClient.logs())
        logsText = j.optString("logs", logsText)
      } catch (_: Exception) {}
      delay(2000)
    }
  }

  MaterialTheme {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            status = launchBridgeBootstrapInTermux(context)
            showPermissionFix = status.startsWith("Faltan permisos")
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

      if (showRestartBridge) {
        Button(onClick = {
          status = launchBridgeBootstrapInTermux(context)
          showPermissionFix = status.startsWith("Faltan permisos")
          step = SetupStep.CREATE_BRIDGE
          pBridge = 0.4f
          showRestartBridge = false
        }, modifier = Modifier.fillMaxWidth()) {
          Text("Reiniciar bridge")
        }
      }

      if (showPermissionFix) {
        Button(onClick = {
          openAppSettings(context, "com.termux")
          status = "Activa permisos y allow-external-apps en Termux, luego vuelve y reintenta."
        }, modifier = Modifier.fillMaxWidth()) {
          Text("Abrir permisos de Termux")
        }
      }

      Button(onClick = { refreshStep() }, modifier = Modifier.fillMaxWidth()) {
        Text("Revisar estado")
      }
    }
  }
}
