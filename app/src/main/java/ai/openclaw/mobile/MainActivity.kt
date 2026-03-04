package ai.openclaw.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
  suspend fun installStart(): String = post("/install/openclaw/start", "{}")
  suspend fun progress(): JSONObject = JSONObject(get("/progress"))

  private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
    val conn = URL(base + path).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 3000
    conn.readTimeout = 5000
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

private enum class SetupStep {
  DOWNLOAD_TERMUX,
  CREATE_BRIDGE,
  INSTALL_OPENCLAW,
  DONE
}

private fun isInstalled(context: Context, pkg: String): Boolean {
  val pm = context.packageManager
  if (pm.getLaunchIntentForPackage(pkg) != null) return true
  return try {
    pm.getPackageInfo(pkg, 0)
    true
  } catch (_: PackageManager.NameNotFoundException) {
    false
  }
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
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
      return
    } catch (_: Exception) {}
  }
}

private fun bridgeBootstrapCommand(): String = """
pkg update -y
pkg install -y python nodejs
mkdir -p ~/openclaw-mobile/termux
cat > ~/openclaw-mobile/termux/bridge_server.py <<'PY'
#!/usr/bin/env python3
import json, subprocess, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

status = {'running': False, 'percent': 0, 'phase': 'idle', 'detail': ''}

def run(cmd, timeout=1200):
    p = subprocess.run(cmd, shell=True, text=True, capture_output=True, timeout=timeout)
    return p.returncode, (p.stdout or '').strip(), (p.stderr or '').strip()

def set_status(percent, phase, detail='', running=True):
    status.update({'running': running, 'percent': percent, 'phase': phase, 'detail': detail})

def install_worker():
    try:
        set_status(5, 'starting', 'Preparando instalación')
        steps = [
            (15, 'node', 'pkg install -y nodejs npm'),
            (40, 'openclaw-cli', 'npm i -g @openclaw/cli || npm i -g openclaw'),
            (70, 'configure', 'openclaw configure --mode local || true'),
            (100, 'done', 'echo OK')
        ]
        for pct, phase, cmd in steps:
            c,o,e = run(cmd, 1200)
            if c != 0:
                set_status(pct, 'error', f'{phase}: {e[:300]}', False)
                return
            set_status(pct, phase, (o or e)[:200], True)
        set_status(100, 'done', 'OpenClaw instalado', False)
    except Exception as ex:
        set_status(100, 'error', str(ex), False)

class H(BaseHTTPRequestHandler):
    def sendj(self, code, payload):
        d = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(d)))
        self.end_headers()
        self.wfile.write(d)

    def do_GET(self):
        if self.path == '/health':
            return self.sendj(200, {'ok': True, 'service': 'termux-bridge'})
        if self.path == '/progress':
            return self.sendj(200, status)
        if self.path == '/models':
            c,o,e = run('openclaw models list', 120)
            return self.sendj(200 if c==0 else 500, {'ok': c==0, 'output': o, 'error': e})
        return self.sendj(404, {'error':'not_found'})

    def do_POST(self):
        n = int(self.headers.get('Content-Length','0'))
        b = json.loads(self.rfile.read(n).decode() if n else '{}')

        if self.path == '/install/openclaw/start':
            if status.get('running'):
                return self.sendj(200, {'ok': True, 'message': 'already_running'})
            threading.Thread(target=install_worker, daemon=True).start()
            return self.sendj(200, {'ok': True, 'message': 'started'})

        if self.path == '/auth':
            provider=(b.get('provider') or '').strip(); token=(b.get('token') or '').strip()
            if not provider or not token:
                return self.sendj(400, {'error':'provider_and_token_required'})
            c,o,e = run(f'openclaw models auth paste-token --provider {provider} --token "{token.replace(chr(34), "\\\"")}"', 180)
            return self.sendj(200 if c==0 else 500, {'ok': c==0, 'output': o, 'error': e})

        return self.sendj(404, {'error':'not_found'})

ThreadingHTTPServer(('127.0.0.1',8765), H).serve_forever()
PY
chmod +x ~/openclaw-mobile/termux/bridge_server.py
pkill -f bridge_server.py >/dev/null 2>&1 || true
nohup python ~/openclaw-mobile/termux/bridge_server.py > ~/openclaw-mobile/termux/bridge.log 2>&1 &
echo BRIDGE_STARTED
""".trimIndent()

private fun launchBridgeBootstrapInTermux(context: Context): String {
  val bootstrap = bridgeBootstrapCommand()
  return try {
    val runIntent = Intent("com.termux.app.RUN_COMMAND").apply {
      setPackage("com.termux")
      putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
      putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", bootstrap))
      putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
      putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
    }
    context.startService(runIntent)
    "Inicializando bridge en Termux..."
  } catch (_: Exception) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
    if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "Abre Termux y permite ejecución externa (RUN_COMMAND)"
  }
}

@Composable
fun App(context: Context) {
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf("Revisando estado...") }
  var step by remember { mutableStateOf(SetupStep.DOWNLOAD_TERMUX) }
  var progress by remember { mutableFloatStateOf(0f) }

  fun buttonLabel(s: SetupStep) = when (s) {
    SetupStep.DOWNLOAD_TERMUX -> "1) Descargar Termux"
    SetupStep.CREATE_BRIDGE -> "2) Crear bridge con Termux"
    SetupStep.INSTALL_OPENCLAW -> "3) Instalar Ubuntu + OpenClaw"
    SetupStep.DONE -> "✅ Listo"
  }

  fun refreshStep() {
    scope.launch {
      if (!isInstalled(context, "com.termux")) {
        step = SetupStep.DOWNLOAD_TERMUX
        progress = 0.05f
        status = "No se detecta Termux"
        return@launch
      }
      val bridgeUp = try {
        TermuxBridgeClient.health()
        true
      } catch (_: Exception) { false }
      if (!bridgeUp) {
        step = SetupStep.CREATE_BRIDGE
        progress = 0.33f
        status = "Termux detectado, bridge aún no disponible"
        return@launch
      }
      step = SetupStep.INSTALL_OPENCLAW
      progress = 0.66f
      status = "Bridge activo. Instala OpenClaw"
    }
  }

  LaunchedEffect(Unit) { refreshStep() }

  MaterialTheme {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Image(
        painter = painterResource(id = R.drawable.lobsterd_logo),
        contentDescription = "Lobsterd logo",
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentScale = ContentScale.Crop
      )

      Text("OpenClaw Mobile", style = MaterialTheme.typography.headlineMedium)

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Estado: $status")
          Text("Paso actual: ${buttonLabel(step)}")
          LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
          Text("Progreso: ${(progress * 100).toInt()}%")
        }
      }

      Button(onClick = {
        when (step) {
          SetupStep.DOWNLOAD_TERMUX -> {
            openTermuxInstallPage(context)
            status = "Te abrí la descarga de Termux"
            progress = 0.10f
          }
          SetupStep.CREATE_BRIDGE -> {
            status = launchBridgeBootstrapInTermux(context)
            scope.launch {
              repeat(20) { i ->
                delay(3000)
                progress = 0.33f + (0.30f * (i + 1) / 20f)
                val up = try { TermuxBridgeClient.health(); true } catch (_: Exception) { false }
                if (up) {
                  step = SetupStep.INSTALL_OPENCLAW
                  status = "Bridge activo ✅"
                  progress = 0.66f
                  return@launch
                }
              }
              status = "Bridge no inició automáticamente. Reabre Termux y reintenta"
            }
          }
          SetupStep.INSTALL_OPENCLAW -> {
            scope.launch {
              status = "Iniciando instalación OpenClaw..."
              try {
                TermuxBridgeClient.installStart()
                repeat(120) {
                  delay(2000)
                  val p = TermuxBridgeClient.progress()
                  val pct = p.optInt("percent", 0)
                  val phase = p.optString("phase", "")
                  val detail = p.optString("detail", "")
                  progress = 0.66f + (0.34f * (pct.coerceIn(0, 100) / 100f))
                  status = "[$pct%] $phase ${if (detail.isNotBlank()) "- $detail" else ""}"
                  if (!p.optBoolean("running", false) && pct >= 100 && phase == "done") {
                    step = SetupStep.DONE
                    progress = 1f
                    status = "OpenClaw instalado ✅"
                    return@launch
                  }
                  if (!p.optBoolean("running", false) && phase == "error") {
                    status = "Error en instalación: $detail"
                    return@launch
                  }
                }
                status = "Timeout leyendo progreso de instalación"
              } catch (e: Exception) {
                status = "Error instalación: ${e.message}"
              }
            }
          }
          SetupStep.DONE -> {
            progress = 1f
            status = "Todo listo ✅"
          }
        }
      }, modifier = Modifier.fillMaxWidth()) {
        Text(buttonLabel(step))
      }

      Button(onClick = { refreshStep() }, modifier = Modifier.fillMaxWidth()) {
        Text("Revisar estado")
      }
    }
  }
}
