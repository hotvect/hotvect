import hotvect.serve as hotvect_serve


def test_disable_litserve_mcp_turns_off_auto_connector(monkeypatch):
    monkeypatch.setattr(hotvect_serve.ls_server, "_MCP_AVAILABLE", True)

    hotvect_serve._disable_litserve_mcp()

    assert hotvect_serve.ls_server._MCP_AVAILABLE is False
