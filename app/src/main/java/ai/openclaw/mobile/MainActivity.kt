package ai.openclaw.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
  suspend fun installOpenClaw(): String = post("/install/openclaw", "{}")

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
    conn.readTimeout = 120000
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
  val byLaunchIntent = pm.getLaunchIntentForPackage(pkg) != null
  if (byLaunchIntent) return true
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
import json, subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

def run(cmd, timeout=120):
    p = subprocess.run(cmd, shell=True, text=True, capture_output=True, timeout=timeout)
    return p.returncode, (p.stdout or '').strip(), (p.stderr or '').strip()

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
        if self.path == '/models':
            c,o,e = run('openclaw models list', 60)
            return self.sendj(200 if c==0 else 500, {'ok': c==0, 'output': o, 'error': e})
        return self.sendj(404, {'error':'not_found'})
    def do_POST(self):
        n = int(self.headers.get('Content-Length','0'))
        b = json.loads(self.rfile.read(n).decode() if n else '{}')
        if self.path == '/install/openclaw':
            cmd = 'npm i -g @openclaw/cli || npm i -g openclaw; openclaw configure --mode local || true; echo OK'
            c,o,e = run(cmd, 1200)
            return self.sendj(200 if c==0 else 500, {'ok': c==0, 'output': o[-4000:], 'error': e[-2000:]})
        if self.path == '/auth':
            provider=(b.get('provider') or '').strip(); token=(b.get('token') or '').strip()
            if not provider or not token: return self.sendj(400, {'error':'provider_and_token_required'})
            c,o,e = run(f'openclaw models auth paste-token --provider {provider} --token "{token.replace(chr(34), "\\\"")}"', 180)
            return self.sendj(200 if c==0 else 500, {'ok': c==0, 'output': o, 'error': e})
        return self.sendj(404, {'error':'not_found'})

HTTPServer(('127.0.0.1',8765), H).serve_forever()
PY
chmod +x ~/openclaw-mobile/termux/bridge_server.py
nohup python ~/openclaw-mobile/termux/bridge_server.py >/data/data/com.termux/files/home/openclaw-mobile/termux/bridge.log 2>&1 &
echo BRIDGE_STARTED
""".trimIndent()

private fun copyBridgeCommand(context: Context) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("bridge_command", bridgeBootstrapCommand()))
  Toast.makeText(context, "Comando de bridge copiado", Toast.LENGTH_SHORT).show()
}

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
    "Creando bridge... espera 10-30s y vuelve a tocar el botón"
  } catch (_: Exception) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
    if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Toast.makeText(context, "Abre Termux y permite RUN_COMMAND si te lo pide", Toast.LENGTH_LONG).show()
    "Termux abierto. Vuelve a tocar el botón para reintentar."
  }
}

@Composable
fun App(context: Context) {
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf("Revisando estado...") }
  var step by remember { mutableStateOf(SetupStep.DOWNLOAD_TERMUX) }

  fun buttonLabel(s: SetupStep) = when (s) {
    SetupStep.DOWNLOAD_TERMUX -> "1) Descargar Termux"
    SetupStep.CREATE_BRIDGE -> "2) Crear bridge con Termux"
    SetupStep.INSTALL_OPENCLAW -> "3) Instalar Ubuntu + OpenClaw"
    SetupStep.DONE -> "✅ Listo (bridge + OpenClaw)"
  }

  fun refreshStep() {
    scope.launch {
      val hasTermux = isInstalled(context, "com.termux")
      if (!hasTermux) {
        step = SetupStep.DOWNLOAD_TERMUX
        status = "No se detecta Termux"
        return@launch
      }
      val bridgeUp = try {
        TermuxBridgeClient.health()
        true
      } catch (_: Exception) {
        false
      }
      if (!bridgeUp) {
        step = SetupStep.CREATE_BRIDGE
        status = "Termux detectado, bridge aún no disponible"
        return@launch
      }
      // Bridge arriba: ya puede instalar OpenClaw (idempotente)
      step = SetupStep.INSTALL_OPENCLAW
      status = "Bridge activo. Ya puedes instalar OpenClaw"
    }
  }

  androidx.compose.runtime.LaunchedEffect(Unit) { refreshStep() }

  MaterialTheme {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Image(
        painter = painterResource(id = R.drawable.lobsterd_logo),
        contentDescription = "Lobsterd logo",
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentScale = ContentScale.Fit
      )
      Text("OpenClaw Mobile", style = MaterialTheme.typography.headlineMedium)
      Text("Flujo guiado de 1 botón")

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Estado: $status")
          Text("Paso actual: ${buttonLabel(step)}")
        }
      }

      Button(onClick = {
        when (step) {
          SetupStep.DOWNLOAD_TERMUX -> {
            openTermuxInstallPage(context)
            status = "Te abrí la descarga de Termux. Instálalo y vuelve aquí."
          }
          SetupStep.CREATE_BRIDGE -> {
            status = launchBridgeBootstrapInTermux(context)
            scope.launch {
              repeat(12) {
                kotlinx.coroutines.delay(3000)
                val up = try { TermuxBridgeClient.health(); true } catch (_: Exception) { false }
                if (up) {
                  step = SetupStep.INSTALL_OPENCLAW
                  status = "Bridge activo ✅"
                  return@launch
                }
              }
              status = "No inició bridge automático. Usa 'Copiar comando bridge' y pégalo en Termux."
            }
          }
          SetupStep.INSTALL_OPENCLAW -> {
            scope.launch {
              status = "Instalando Ubuntu/OpenClaw..."
              status = try {
                TermuxBridgeClient.installOpenClaw()
                step = SetupStep.DONE
                "OpenClaw instalado ✅"
              } catch (e: Exception) {
                "Error instalando OpenClaw: ${e.message}"
              }
            }
          }
          SetupStep.DONE -> status = "Todo listo ✅"
        }
      }, modifier = Modifier.fillMaxWidth()) {
        Text(buttonLabel(step))
      }

      if (step == SetupStep.CREATE_BRIDGE) {
        Button(onClick = { copyBridgeCommand(context) }, modifier = Modifier.fillMaxWidth()) {
          Text("Copiar comando bridge")
        }
      }

      Button(onClick = { refreshStep() }, modifier = Modifier.fillMaxWidth()) {
        Text("Revisar estado")
      }
    }
  }
}
