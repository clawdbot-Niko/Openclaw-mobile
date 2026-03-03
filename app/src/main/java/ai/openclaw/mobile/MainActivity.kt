package ai.openclaw.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

private fun isInstalled(context: Context, pkg: String): Boolean = try {
  context.packageManager.getPackageInfo(pkg, 0)
  true
} catch (_: PackageManager.NameNotFoundException) {
  false
}

private fun openTermuxInstallPage(context: Context) {
  val links = listOf(
    "market://details?id=com.termux",
    "https://f-droid.org/packages/com.termux/",
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

private fun launchBridgeBootstrapInTermux(context: Context): String {
  val bootstrap = "pkg update -y && pkg install -y git python && cd ~ && (test -d openclaw-mobile || git clone https://github.com/clawdbot-Niko/Openclaw-mobile.git openclaw-mobile) && cd ~/openclaw-mobile/termux && chmod +x *.sh && ./start_bridge.sh"
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

      Button(onClick = { refreshStep() }, modifier = Modifier.fillMaxWidth()) {
        Text("Revisar estado")
      }
    }
  }
}
