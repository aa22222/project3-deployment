#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$PROJECT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
    while IFS='=' read -r key value || [[ -n "${key:-}${value:-}" ]]; do
        [[ -z "${key:-}" || "$key" == \#* ]] && continue

        case "$key" in
            DB_URL|DB_USER|DB_PASSWORD) ;;
            *) printf 'Unsupported setting in .env: %s\n' "$key" >&2; exit 1 ;;
        esac

        # Preserve variables already supplied by the shell; otherwise read the local .env value.
        if [[ -z "${!key:-}" ]]; then
            export "$key=$value"
        fi
    done < "$ENV_FILE"
fi

: "${DB_URL:?Set DB_URL in .env or the environment}"
: "${DB_USER:?Set DB_USER in .env or the environment}"
: "${DB_PASSWORD:?Set DB_PASSWORD in .env or the environment}"

cd "$PROJECT_DIR"
mkdir -p build
javac -cp postgresql-42.2.8.jar -d build GUI/*.java
exec java -cp "build:postgresql-42.2.8.jar" GUI
