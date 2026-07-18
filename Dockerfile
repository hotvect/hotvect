ARG BASE_IMAGE=ghcr.io/astral-sh/uv:python3.11-bookworm-slim
ARG BASE_IMAGE_VERSION=latest
ARG BASE_JDK_IMAGE=eclipse-temurin:21-jdk
ARG BASE_JDK_IMAGE_VERSION=latest

FROM ${BASE_JDK_IMAGE}:${BASE_JDK_IMAGE_VERSION} AS jdk

FROM ${BASE_IMAGE}:${BASE_IMAGE_VERSION}

ENV PYTHONUNBUFFERED=1

ARG UV_DEFAULT_INDEX
ARG UV_INDEX_STRATEGY
ARG S5CMD_VERSION=2.3.0

# Copy JDK 21 into the image (avoid distro package availability issues).
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN apt-get update && \
    apt-get install -y \
    build-essential \
    ca-certificates \
    curl \
    git && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install package dependencies and optional CA certificates.
COPY cdp-certs /usr/local/share/ca-certificates/cdp
RUN update-ca-certificates

RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) s5cmd_arch="64bit" ;; \
        arm64) s5cmd_arch="arm64" ;; \
        *) echo "Unsupported architecture for s5cmd: $arch" >&2; exit 1 ;; \
    esac; \
    archive="s5cmd_${S5CMD_VERSION}_Linux-${s5cmd_arch}.tar.gz"; \
    tmp_dir="$(mktemp -d)"; \
    curl -fsSL "https://github.com/peak/s5cmd/releases/download/v${S5CMD_VERSION}/${archive}" -o "${tmp_dir}/${archive}"; \
    curl -fsSL "https://github.com/peak/s5cmd/releases/download/v${S5CMD_VERSION}/s5cmd_checksums.txt" -o "${tmp_dir}/s5cmd_checksums.txt"; \
    cd "$tmp_dir"; \
    grep "  ${archive}$" s5cmd_checksums.txt > s5cmd_checksum.txt; \
    sha256sum -c s5cmd_checksum.txt; \
    tar -xzf "$archive" -C /usr/local/bin s5cmd; \
    chmod +x /usr/local/bin/s5cmd; \
    cd /; \
    rm -rf "$tmp_dir"; \
    s5cmd version

# The python base image used in CDP provides `uv` out of the box.
RUN set -eux; \
    command -v uv; \
    uv --version

RUN mkdir /opt/hotvect
COPY python /opt/hotvect
WORKDIR /opt/hotvect
RUN set -eux; \
    index_args=""; \
    if [ -n "${UV_DEFAULT_INDEX:-}" ]; then index_args="$index_args --default-index $UV_DEFAULT_INDEX"; fi; \
    if [ -n "${UV_INDEX_STRATEGY:-}" ]; then index_args="$index_args --index-strategy $UV_INDEX_STRATEGY"; fi; \
    # Keep behavior close to v9: the base training image installs only the SageMaker-related extras.
    # Heavyweight ML runtimes are installed in dedicated training image variants.
    uv sync --frozen --no-dev --extra sagemaker ${index_args}
ENV PATH="/opt/hotvect/.venv/bin:$PATH"

ENTRYPOINT ["sagemaker-entrypoint"]
