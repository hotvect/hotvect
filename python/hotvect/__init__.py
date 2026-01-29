try:
    from importlib.metadata import version

    __version__ = version("hotvect")
except Exception:
    __version__ = "unknown"
