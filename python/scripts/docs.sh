#!/usr/bin/env bash
set -euo pipefail

cmd="${1:-}"
shift || true

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdocs_config="${root_dir}/mkdocs.yml"

if [[ -z "${cmd}" ]]; then
  echo "usage: $(basename "$0") {build|serve} [mkdocs args...]" >&2
  exit 2
fi

run_mkdocs() {
  local subcmd="$1"
  shift || true

  if command -v mkdocs >/dev/null 2>&1; then
	    # pyenv shims may expose `mkdocs` even when the mkdocs env isn't selected.
	    # Also, MkDocs 2.x currently emits a Material compatibility warning that breaks strict builds.
	    local mkdocs_version
	    mkdocs_version="$(mkdocs --version 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="version") {print $(i+1); exit}}' || true)"
	    if [[ -n "${mkdocs_version}" ]]; then
	      local mkdocs_major
	      mkdocs_major="${mkdocs_version%%.*}"
	      if [[ "${mkdocs_major}" =~ ^[0-9]+$ ]] && [[ "${mkdocs_major}" -lt 2 ]]; then
        (cd "${root_dir}" && mkdocs "${subcmd}" -f "${mkdocs_config}" "$@")
        return
      fi
    fi
  fi

  if command -v uv >/dev/null 2>&1; then
    (
      cd "${root_dir}/python"
      # Pin to versions that don't emit compatibility warnings under strict builds.
      env -u VIRTUAL_ENV uv run --with "mkdocs==1.6.1" --with "mkdocs-material==9.5.49" --with pymdown-extensions \
        mkdocs "${subcmd}" -f "${mkdocs_config}" "$@"
    )
    return
  fi

  echo "mkdocs not found (and uv not available). Install mkdocs or use a mkdocs pyenv." >&2
  exit 1
}

case "${cmd}" in
  build)
    run_mkdocs build -q "$@"
    ;;
  serve)
    # Use a non-default port to avoid collisions with other local dev services on :8000.
    run_mkdocs serve -a 127.0.0.1:8001 "$@"
    ;;
  *)
    echo "unknown subcommand: ${cmd} (expected: build|serve)" >&2
    exit 2
    ;;
esac
