#!/usr/bin/env bash
set -euo pipefail
task_repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
task_key_dir="$task_repo_dir/.private/signing"
umask 077
mkdir -p "$task_key_dir"
if [[ -f "$task_key_dir/xtremex-tv.jks" ]]; then
  echo "Permanent signing key already exists; kept unchanged."
  exit 0
fi
if [[ -f "$task_key_dir/store-password.txt" || -f "$task_key_dir/key-password.txt" ]]; then
  echo "Incomplete signing setup exists; inspect it before creating a key." >&2
  exit 1
fi
python3 - "$task_key_dir" <<'PY'
from pathlib import Path
import secrets,sys
directory=Path(sys.argv[1])
for name in ['store-password.txt','key-password.txt']:
    (directory/name).write_text(secrets.token_urlsafe(32))
(directory/'alias.txt').write_text('xtremex-tv')
PY
keytool -genkeypair -alias xtremex-tv -keyalg RSA -keysize 4096 -validity 10000 \
  -dname 'CN=XtremeX TV,O=XtremeX Communication Solutions,C=BD' \
  -storetype JKS -keystore "$task_key_dir/xtremex-tv.jks" \
  -storepass:file "$task_key_dir/store-password.txt" -keypass:file "$task_key_dir/key-password.txt"
echo "Permanent signing key created in the private signing directory."
