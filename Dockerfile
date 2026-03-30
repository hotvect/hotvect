ARG BASE_IMAGE=python:3.11-slim
ARG BASE_IMAGE_VERSION=latest
ARG BASE_JDK_IMAGE=eclipse-temurin:21-jdk
ARG BASE_JDK_IMAGE_VERSION=latest

FROM ${BASE_JDK_IMAGE}:${BASE_JDK_IMAGE_VERSION} AS jdk

FROM ${BASE_IMAGE}:${BASE_IMAGE_VERSION}

ENV PYTHONUNBUFFERED=1

ARG UV_DEFAULT_INDEX
ARG UV_INDEX_STRATEGY

# Copy JDK 21 into the image (avoid distro package availability issues).
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN apt-get update && \
    apt-get install -y \
    build-essential \
    ca-certificates \
    git && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# The python base image used in CDP provides `uv` out of the box.
RUN set -eux; \
    command -v uv; \
    uv --version

# Configue Nexus access and install packages
# https://example.com/docs/faq/#docker-build-is-unable-to-access-nexus-from-cdp
COPY cdp-certs /usr/local/share/ca-certificates/cdp
RUN update-ca-certificates

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
