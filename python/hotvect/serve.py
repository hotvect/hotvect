"""
Hotvect inference server scaffolding based on LitServe.

Provides:
- HotvectLitAPI: Base class for hotvect inference APIs
- run_server: Start a LitServe server with standard configuration
"""

from __future__ import annotations

import logging
from abc import abstractmethod
from typing import Any, Callable

import litserve as ls
import litserve.server as ls_server

log = logging.getLogger(__name__)


def _disable_litserve_mcp() -> None:
    if getattr(ls_server, "_MCP_AVAILABLE", False):
        log.info("Disabling LitServe MCP integration for Hotvect")
        ls_server._MCP_AVAILABLE = False


class HotvectLitAPI(ls.LitAPI):
    """Base class for hotvect inference servers."""

    def __init__(self, model_path: str, **kwargs):
        super().__init__(**kwargs)
        self.model_path = model_path
        self.model = None

    @abstractmethod
    def load_model(self, device: str):
        raise NotImplementedError

    def setup(self, device: str):
        log.info("Setting up HotvectLitAPI on device=%s model_path=%s", device, self.model_path)
        self.model = self.load_model(device)
        log.info("Model loaded successfully")

    @abstractmethod
    def decode_request(self, request) -> Any:
        raise NotImplementedError

    @abstractmethod
    def predict(self, inputs):
        raise NotImplementedError

    @abstractmethod
    def encode_response(self, scores):
        raise NotImplementedError


def run_server(
    api: ls.LitAPI,
    *,
    host: str = "127.0.0.1",
    port: int = 8000,
    workers: int = 1,
    device: str = "auto",
    devices: int | str = "auto",
    timeout: float = 30.0,
    log_level: str = "info",
    configure_app: Callable[[Any], None] | None = None,
):
    log.info("Starting hotvect inference server on %s:%s", host, port)
    log.info("Workers per device: %s, Device: %s, Devices: %s, Timeout: %ss", workers, device, devices, timeout)

    _disable_litserve_mcp()
    server = ls.LitServer(
        api,
        accelerator=device,
        devices=devices,
        workers_per_device=workers,
        timeout=timeout,
    )

    if configure_app is not None:
        configure_app(server.app)

    server.run(host=host, port=port, log_level=log_level, generate_client_file=False)
