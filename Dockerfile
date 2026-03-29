ARG BASE_IMAGE=python:3.11-slim
ARG BASE_IMAGE_VERSION=latest
ARG BASE_JDK_IMAGE=eclipse-temurin:21-jdk
ARG BASE_JDK_IMAGE_VERSION=latest

FROM ${BASE_JDK_IMAGE}:${BASE_JDK_IMAGE_VERSION} AS jdk

# 2) Final stage: Python image
FROM ${BASE_IMAGE}:${BASE_IMAGE_VERSION}

ENV PYTHONUNBUFFERED=1

ARG PIP_INDEX_URL=https://example.com/pypi/simple
ARG PIP_TRUSTED_HOST=example.com

# copy JDK 21 into the image
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

COPY cdp-certs /usr/local/share/ca-certificates/cdp

RUN apt-get update && \
    apt-get install -y \
    build-essential \
    git && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Configue Nexus access and install packages
# https://example.com/docs/faq/#docker-build-is-unable-to-access-nexus-from-cdp
COPY ./cdp-certs /usr/local/share/ca-certificates/cdp
RUN update-ca-certificates

RUN mkdir /opt/hotvect
COPY python /opt/hotvect
WORKDIR /opt/hotvect
RUN uv sync --locked --all-extras
ENV PATH="/opt/hotvect/.venv/bin:$PATH"

ENTRYPOINT ["sagemaker-entrypoint"]
