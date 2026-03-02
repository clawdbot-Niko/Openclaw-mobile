# OpenClaw Mobile (APK) - Alpha

APK Android con conexión real a Termux (bridge local HTTP).

## Qué ya integra (alpha 0.3)
- UI Android (Compose)
- Botón de **autobridge**: intenta abrir/usar Termux y levantar bridge
- Botón de conexión real con Termux bridge (`/health`)
- Instalador real por Termux (`/install/openclaw`)
- Catálogo real de modelos (`openclaw models list`)
- Envío de OAuth/API token a OpenClaw (`/auth`)

## Preparación en Termux (una sola vez)

```bash
pkg update -y
pkg install -y git python
cd ~
git clone https://github.com/clawdbot-Niko/Openclaw-mobile.git openclaw-mobile
cd openclaw-mobile/termux
chmod +x *.sh
./start_bridge.sh
```

Esto levanta el bridge en `http://127.0.0.1:8765`.

## Flujo en la app
1. Probar conexión Termux
2. Instalar Ubuntu/OpenClaw
3. Cargar catálogo real
4. Enviar OAuth/API

## Build APK por GitHub Actions
- Revisa Actions en el repo.
- Descarga artifact `app-debug-apk`.
