#!/data/data/com.termux/files/usr/bin/bash
set -e

cd ~/openclaw-mobile/termux

# Ensure base deps
pkg update -y
pkg install -y python

# Start bridge
python bridge_server.py
