# OpenClaw Mobile (APK) - Alpha

Primer esqueleto de app Android completa para instalar y operar OpenClaw desde UI.

## Estado actual
- Base Android + Jetpack Compose
- Wizard inicial con botones de flujo:
  - Instalar Linux + OpenClaw
  - Detectar modelos
  - Instalar fallback Ollama
  - Instalar extras (whisper/tts)

## Siguiente paso inmediato
Conectar cada botón a ejecución real en Termux (bridge por intent o localhost).

## Build local (Android Studio)
1. Abrir carpeta `openclaw-mobile/` en Android Studio Hedgehog+.
2. Sincronizar Gradle.
3. Run en dispositivo Android o emulador.
4. Para APK debug:
   - `Build > Build Bundle(s) / APK(s) > Build APK(s)`

APK de debug quedará en:
`app/build/outputs/apk/debug/app-debug.apk`
