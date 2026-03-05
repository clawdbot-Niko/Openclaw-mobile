package ai.openclaw.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { App(this) }
  }
}

private const val PKG_TERMUX = "com.termux"
private const val PKG_TERMUX_API = "com.termux.api"

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

private fun openUrl(context: Context, url: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
  } catch (_: Exception) {}
}

private fun openAppSettings(context: Context, packageName: String) {
  try {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:$packageName")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
  } catch (_: Exception) {}
}

private fun copyToClipboard(context: Context, label: String, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun openTermux(context: Context): Boolean {
  val launch = context.packageManager.getLaunchIntentForPackage(PKG_TERMUX) ?: return false
  return try {
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
  } catch (_: Exception) {
    false
  }
}

private fun cmdInstallUbuntu(): String = """
# Paso 5 - Instalar Ubuntu (proot-distro)
set -e
pkg update -y
pkg install -y proot-distro
proot-distro install ubuntu

# Entrar a Ubuntu:
# proot-distro login ubuntu
""".trimIndent()

private fun cmdInstallOpenClawInTermux(): String = """
# Paso 6 - Instalar OpenClaw (RECOMENDADO: en Termux, no en Ubuntu)
set -e
pkg update -y
pkg install -y nodejs
npm i -g openclaw

# Iniciar login:
# openclaw onboard
""".trimIndent()

@Composable
fun App(context: Context) {
  var status by remember { mutableStateOf("Listo") }
  val scroll = rememberScrollState()

  MaterialTheme {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .verticalScroll(scroll),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Image(
        painter = painterResource(id = R.drawable.lobsterd_logo),
        contentDescription = "logo",
        modifier = Modifier
          .fillMaxWidth()
          .height(170.dp),
        contentScale = ContentScale.Crop
      )

      Text("OpenClaw Mobile", style = MaterialTheme.typography.headlineMedium)

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Estado: $status")
          Text("Esta app ahora solo te muestra pasos y te copia comandos.")
        }
      }

      StepCard(
        title = "1) Instalar F-Droid",
        body = "Abre el link oficial para descargar F-Droid.",
        primaryLabel = "Abrir descarga",
        onPrimary = {
          openUrl(context, "https://f-droid.org/F-Droid.apk")
          status = "Abriendo descarga de F-Droid…"
        }
      )

      StepCard(
        title = "2) Instalar Termux (desde F-Droid)",
        body = "Instala Termux desde F-Droid (recomendado).",
        primaryLabel = "Abrir Termux en F-Droid",
        onPrimary = {
          openUrl(context, "https://f-droid.org/packages/com.termux/")
          status = "Abriendo Termux en F-Droid…"
        }
      )

      StepCard(
        title = "3) Instalar Termux:API (desde F-Droid)",
        body = "Instala el complemento Termux:API.",
        primaryLabel = "Abrir Termux:API en F-Droid",
        onPrimary = {
          openUrl(context, "https://f-droid.org/packages/com.termux.api/")
          status = "Abriendo Termux:API en F-Droid…"
        }
      )

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("4) Permisos en Android", style = MaterialTheme.typography.titleMedium)
          Text(
            "Abre ajustes para dar permisos. Recomendado:\n" +
              "• Termux: Batería sin restricciones\n" +
              "• Termux: Permisos que pida (archivos/notifications)\n" +
              "• (Opcional) OpenClaw Mobile: notificaciones"
          )

          Row(Modifier.fillMaxWidth()) {
            Button(
              onClick = {
                openAppSettings(context, PKG_TERMUX)
                status = "Ajustes de Termux abiertos."
              },
              modifier = Modifier.weight(1f)
            ) { Text("Ajustes Termux") }
            Spacer(Modifier.width(8.dp))
            Button(
              onClick = {
                openAppSettings(context, PKG_TERMUX_API)
                status = "Ajustes de Termux:API abiertos."
              },
              modifier = Modifier.weight(1f)
            ) { Text("Ajustes Termux:API") }
          }

          Button(
            onClick = {
              openAppSettings(context, context.packageName)
              status = "Ajustes de la app abiertos."
            },
            modifier = Modifier.fillMaxWidth()
          ) { Text("Ajustes OpenClaw Mobile") }
        }
      }

      StepCard(
        title = "5) Instalar Ubuntu (en Termux)",
        body = "Copia el comando, abre Termux y pégalo.",
        primaryLabel = "Copiar comando",
        onPrimary = {
          copyToClipboard(context, "install_ubuntu", cmdInstallUbuntu())
          status = "Comando copiado. Abre Termux y pega."
        },
        secondaryLabel = "Abrir Termux",
        onSecondary = {
          if (!openTermux(context)) status = "No se encontró Termux instalado."
        }
      )

      StepCard(
        title = "6) Instalar OpenClaw (en Termux)",
        body = "Copia el comando, abre Termux y pégalo.\n\nNota: en Ubuntu/proot puede fallar por red; por eso es recomendado en Termux.",
        primaryLabel = "Copiar comando",
        onPrimary = {
          copyToClipboard(context, "install_openclaw", cmdInstallOpenClawInTermux())
          status = "Comando copiado. Abre Termux y pega."
        },
        secondaryLabel = "Abrir Termux",
        onSecondary = {
          if (!openTermux(context)) status = "No se encontró Termux instalado."
        }
      )

      Button(
        onClick = {
          val okT = isInstalled(context, PKG_TERMUX)
          val okApi = isInstalled(context, PKG_TERMUX_API)
          status = "Checklist: Termux=${if (okT) "OK" else "NO"}, Termux:API=${if (okApi) "OK" else "NO"}" 
        },
        modifier = Modifier.fillMaxWidth()
      ) { Text("Revisar instalación") }
    }
  }
}

@Composable
private fun StepCard(
  title: String,
  body: String,
  primaryLabel: String,
  onPrimary: () -> Unit,
  secondaryLabel: String? = null,
  onSecondary: (() -> Unit)? = null,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(body)
      Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
        Text(primaryLabel)
      }
      if (secondaryLabel != null && onSecondary != null) {
        Button(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
          Text(secondaryLabel)
        }
      }
    }
  }
}
