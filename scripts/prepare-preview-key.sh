#!/usr/bin/env bash
# Run interactively by the owner only after approving persistent-key generation.
set +x
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
[ -t 0 ] && [ -t 1 ] && [ -t 2 ] || {
  echo "Run this yourself in an interactive terminal without pipes or output redirection."
  exit 1
}
command -v keytool >/dev/null || { echo "Set JAVA_HOME/PATH to JDK 17 first."; exit 1; }
mkdir -p .signing
chmod 700 .signing
for target in .signing/preview.p12 .signing/preview-keystore.base64 .signing/preview-public.pem .signing/preview-certificate.txt; do
  [ ! -e "$target" ] || { echo "A signing file already exists; preserve it and inspect manually."; exit 1; }
done
keytool -genkeypair -storetype PKCS12 -keystore .signing/preview.p12 \
  -alias fluxa-preview -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Fluxa Preview"
python3 - <<'PY'
import base64
from pathlib import Path
Path(".signing/preview-keystore.base64").write_bytes(
    base64.b64encode(Path(".signing/preview.p12").read_bytes()))
PY
keytool -exportcert -rfc -keystore .signing/preview.p12 -alias fluxa-preview \
  -file .signing/preview-public.pem
# This command reads only a public certificate and never prompts for a password.
keytool -printcert -file .signing/preview-public.pem > .signing/preview-certificate.txt
chmod 600 .signing/preview.p12 .signing/preview-keystore.base64 .signing/preview-public.pem .signing/preview-certificate.txt
echo "Files prepared locally in .signing/. Back up the keystore and password securely."
echo "Upload credentials yourself using docs/releases.md. Do not send them in chat."
