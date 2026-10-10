#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
cd "${ROOT_DIR}"

export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}"

BASE_IMAGE="${BASE_IMAGE:-ghcr.io/astral-sh/uv:python3.11-bookworm-slim}"
BASE_IMAGE_VERSION="${BASE_IMAGE_VERSION:-latest}"

# Local builds default to CPU wheels to avoid pulling CUDA runtimes.
HOTVECT_TORCH_BACKEND="${HOTVECT_TORCH_BACKEND:-cpu}"
# Optional: set DOCKER_NETWORK to build on a specific Docker network.
DOCKER_NETWORK="${DOCKER_NETWORK:-}"

BASE_TAG="${BASE_TAG:-hotvect:base-test}"
TF_TAG="${TF_TAG:-hotvect:tensorflow-test}"
TORCH_TAG="${TORCH_TAG:-hotvect:torch-test}"
TORCH_TF_TAG="${TORCH_TF_TAG:-hotvect:torch-tensorflow-test}"

network_args=()
if [[ -n "${DOCKER_NETWORK}" ]]; then
  network_args+=(--network "${DOCKER_NETWORK}")
fi

extra_build_args=()
extra_build_args+=(--build-arg "BASE_IMAGE=${BASE_IMAGE}")
extra_build_args+=(--build-arg "BASE_IMAGE_VERSION=${BASE_IMAGE_VERSION}")
if [[ -n "${UV_DEFAULT_INDEX:-}" ]]; then
  extra_build_args+=(--build-arg "UV_DEFAULT_INDEX=${UV_DEFAULT_INDEX}")
fi
if [[ -n "${UV_INDEX_STRATEGY:-}" ]]; then
  extra_build_args+=(--build-arg "UV_INDEX_STRATEGY=${UV_INDEX_STRATEGY}")
fi

set -x
docker build ${network_args[@]+"${network_args[@]}"} --platform linux/amd64 -t "${BASE_TAG}" "${extra_build_args[@]}" .
docker build ${network_args[@]+"${network_args[@]}"} --platform linux/amd64 -f Dockerfile.tensorflow -t "${TF_TAG}" --build-arg "HOTVECT_BASE_IMAGE=${BASE_TAG}" .
docker build ${network_args[@]+"${network_args[@]}"} --platform linux/amd64 -f Dockerfile.torch -t "${TORCH_TAG}" \
  --build-arg "HOTVECT_BASE_IMAGE=${BASE_TAG}" \
  --build-arg "HOTVECT_TORCH_BACKEND=${HOTVECT_TORCH_BACKEND}" \
  .
docker build ${network_args[@]+"${network_args[@]}"} --platform linux/amd64 -f Dockerfile.torch-tensorflow -t "${TORCH_TF_TAG}" --build-arg "HOTVECT_BASE_IMAGE=${TORCH_TAG}" .
