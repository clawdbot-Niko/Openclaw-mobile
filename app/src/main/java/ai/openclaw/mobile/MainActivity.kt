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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

private fun startTermuxBridge(context: Context): String {
  if (!isInstalled(context, "com.termux")) {
    openTermuxInstallPage(context)
    return "Termux no está instalado. Te abrí la descarga."
  }

  val bootstrap = "pkg update -y && pkg install -y git python && cd ~ && (test -d openclaw-mobile || git clone https://github.com/clawdbot-Niko/Openclaw-mobile.git openclaw-mobile) && cd ~/openclaw-mobile/termux && chmod +x *.sh && ./start_bridge.sh"

  return try {
    // Intenta lanzar comando directo en Termux
    val runIntent = Intent("com.termux.app.RUN_COMMAND").apply {
      setPackage("com.termux")
      putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
      putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", bootstrap))
      putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
      putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
    }
    context.startService(runIntent)
    "Creando bridge en Termux..."
  } catch (e: Exception) {
    // Fallback: abrir Termux por si requiere permiso de ejecución externa
    try {
      val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
      if (launch != null) {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    } catch (_: Exception) {}
    Toast.makeText(context, "Abre Termux y acepta ejecución externa si te la pide", Toast.LENGTH_LONG).show()
    "Termux abierto. Si no inicia solo, reintenta el botón."
  }
}

private object TermuxBridgeClient {
  private const val base = "http://127.0.0.1:8765"

  suspend fun health(): String = get("/health")
  suspend fun installOpenClaw(): String = post("/install/openclaw", "{}")
  suspend fun listModels(): String = get("/models")
  suspend fun setAuth(provider: String, token: String): String =
    post("/auth", JSONObject().put("provider", provider).put("token", token).toString())

  private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
    val conn = URL(base + path).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 8_000
    conn.readTimeout = 30_000
    conn.inputStream.bufferedReader().use(BufferedReader::readText)
  }

  private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
    val conn = URL(base + path).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.connectTimeout = 8_000
    conn.readTimeout = 120_000
    conn.doOutput = true
    OutputStreamWriter(conn.outputStream).use { it.write(body) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    stream.bufferedReader().use(BufferedReader::readText)
  }
}

@Composable
fun App(context: Context) {
  var status by remember { mutableStateOf("Listo") }
  var models by remember { mutableStateOf("Sin cargar") }
  var provider by remember { mutableStateOf("openai-codex") }
  var token by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  MaterialTheme {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("OpenClaw Mobile", style = MaterialTheme.typography.headlineMedium)
      Text("Alpha 0.2 - Termux real bridge")

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Estado: $status")
          Text("Modelos: $models")
        }
      }

      Button(onClick = {
        status = startTermuxBridge(context)
      }, modifier = Modifier.fillMaxWidth()) {
        Text("Crear bridge automáticamente (Termux)")
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
          scope.launch {
            status = "Revisando bridge..."
            status = try { "Bridge OK: ${TermuxBridgeClient.health()}" } catch (e: Exception) { "Bridge no disponible: ${e.message}" }
          }
        }, modifier = Modifier.weight(1f)) {
          Text("Probar conexión Termux")
        }
      }

      Button(onClick = {
        scope.launch {
          status = "Instalando Ubuntu/OpenClaw por Termux..."
          status = try { TermuxBridgeClient.installOpenClaw() } catch (e: Exception) { "Error: ${e.message}" }
        }
      }, modifier = Modifier.fillMaxWidth()) {
        Text("Instalar Ubuntu + OpenClaw")
      }

      Button(onClick = {
        scope.launch {
          status = "Leyendo catálogo real..."
          models = try { TermuxBridgeClient.listModels() } catch (e: Exception) { "Error: ${e.message}" }
          status = "Catálogo actualizado"
        }
      }, modifier = Modifier.fillMaxWidth()) {
        Text("Cargar catálogo real de modelos")
      }

      OutlinedTextField(
        value = provider,
        onValueChange = { provider = it },
        label = { Text("Provider (ej: openai-codex / openrouter / ollama)") },
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("OAuth/API token") },
        modifier = Modifier.fillMaxWidth()
      )

      Button(onClick = {
        scope.launch {
          status = "Guardando token en OpenClaw..."
          status = try { TermuxBridgeClient.setAuth(provider, token) } catch (e: Exception) { "Error: ${e.message}" }
        }
      }, modifier = Modifier.fillMaxWidth()) {
        Text("Enviar OAuth/API a Termux")
      }
    }
  }
}
