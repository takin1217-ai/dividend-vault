from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
SECRETS = ROOT / "secrets"
BACKUPS = DATA / "backups"
LOGS = DATA / "logs"
DB_PATH = DATA / "vault.sqlite"
SCHEMA_PATH = ROOT / "shared" / "schema.sql"
LOCK_PATH = DATA / "sync.lock"


def ensure_dirs() -> None:
    for path in (DATA, SECRETS, BACKUPS, LOGS):
        path.mkdir(parents=True, exist_ok=True)
