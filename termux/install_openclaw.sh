#!/data/data/com.termux/files/usr/bin/bash
set -e

pkg update -y
pkg install -y curl git jq nodejs python openssh

# OpenClaw install (idempotente)
if ! command -v openclaw >/dev/null 2>&1; then
  npm i -g @openclaw/cli || npm i -g openclaw
fi

# Config básica local
openclaw configure --mode local || true

# Ollama fallback opcional (solo si existe paquete/comando)
if command -v ollama >/dev/null 2>&1; then
  ollama pull qwen2.5:0.5b || true
  openclaw config set agents.defaults.model.fallbacks '["openai-codex/gpt-5.3-codex","ollama/qwen2.5:0.5b"]' || true
fi

echo "OK: OpenClaw instalado/configurado"
