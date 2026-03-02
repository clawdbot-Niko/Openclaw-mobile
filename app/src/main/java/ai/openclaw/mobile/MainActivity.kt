package ai.openclaw.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { App() }
  }
}

@Composable
fun App() {
  var status by remember { mutableStateOf("Listo para instalar") }
  var model by remember { mutableStateOf("openai-codex/gpt-5.3-codex") }

  MaterialTheme {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("OpenClaw Mobile", style = MaterialTheme.typography.headlineMedium)
      Text("Alpha 0.1 - Wizard base")

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Estado: $status")
          Text("Modelo actual: $model")
        }
      }

      Button(onClick = { status = "Instalando Linux + OpenClaw..." }, modifier = Modifier.fillMaxWidth()) {
        Text("Instalar entorno Linux + OpenClaw")
      }
      Button(onClick = { status = "Detectando modelos disponibles..." }, modifier = Modifier.fillMaxWidth()) {
        Text("Detectar modelos")
      }
      Button(onClick = {
        model = "ollama/qwen2.5:0.5b"
        status = "Fallback Ollama configurado"
      }, modifier = Modifier.fillMaxWidth()) {
        Text("Instalar fallback Ollama")
      }
      Button(onClick = { status = "Instalando extras: whisper/tts..." }, modifier = Modifier.fillMaxWidth()) {
        Text("Instalar librerías extra")
      }

      Spacer(Modifier.height(10.dp))
      Text("Siguiente: conectar esta UI con comandos reales (Termux bridge).")
    }
  }
}
