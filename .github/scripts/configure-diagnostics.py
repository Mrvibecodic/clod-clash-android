#!/usr/bin/env python3
import json
import os
from pathlib import Path

fingerprint = os.getenv("DIAGNOSTICS_FINGERPRINT", "").strip()
generated = Path("core/src/main/golang/native/diagnostics_credentials_generated.go")
if fingerprint:
    generated.write_text(
        f"package main\n\nfunc init() {{ diagnosticsFingerprint = {json.dumps(fingerprint)} }}\n",
        encoding="utf-8",
    )
else:
    generated.unlink(missing_ok=True)
