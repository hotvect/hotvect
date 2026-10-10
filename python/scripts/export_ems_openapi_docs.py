"""Export an EMS OpenAPI document into the Hotvect docsite.

Usage:
    python scripts/export_ems_openapi_docs.py /path/to/api.yaml

The EMS server remains the owner of the contract. This script vendors a generated
OpenAPI export and creates a readable, static reference for the matching Hotvect
documentation release.
"""

from __future__ import annotations

import argparse
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import yaml

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIRECTORY = REPOSITORY_ROOT / "python/hotvect/mcp/bundled_docs/docs/reference/ems-server-api"
RAW_SPEC_PATH = OUTPUT_DIRECTORY / "openapi.yaml"
REFERENCE_PATH = OUTPUT_DIRECTORY / "index.md"
HTTP_METHODS = ("get", "put", "post", "patch", "delete", "head", "options")
CONSTRAINTS = ("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "pattern")


def _escape_markdown(value: Any) -> str:
    if value is None:
        return "—"
    return str(value).replace("|", "\\|").replace("\n", "<br>").strip() or "—"


def _reference_name(reference: str) -> str:
    return reference.rsplit("/", maxsplit=1)[-1]


def _format_schema(schema: Mapping[str, Any] | None) -> str:
    if not schema:
        return "—"
    if "$ref" in schema:
        return f"`{_reference_name(schema['$ref'])}`"
    if "oneOf" in schema:
        return "one of " + ", ".join(_format_schema(item) for item in schema["oneOf"])

    schema_type = schema.get("type", "object" if "properties" in schema else "any")
    if schema_type == "array":
        return f"array of {_format_schema(schema.get('items'))}"

    result = f"`{schema_type}`"
    if schema.get("format"):
        result += f" (`{schema['format']}`)"
    if schema.get("enum"):
        result += " — " + ", ".join(f"`{value}`" for value in schema["enum"])
    return result


def _format_constraints(schema: Mapping[str, Any]) -> str:
    values = [f"`{name}` = `{schema[name]}`" for name in CONSTRAINTS if name in schema]
    return ", ".join(values) if values else "—"


def _format_security(requirements: list[Mapping[str, list[str]]] | None) -> str:
    if not requirements:
        return "—"
    values = []
    for requirement in requirements:
        for scheme, scopes in requirement.items():
            values.append(f"`{scheme}`" + (f" ({', '.join(f'`{scope}`' for scope in scopes)})" if scopes else ""))
    return "; ".join(values)


def _content_schema(content: Mapping[str, Any] | None) -> str:
    if not content:
        return "—"
    return "; ".join(
        f"`{content_type}`: {_format_schema(media_type.get('schema'))}" for content_type, media_type in content.items()
    )


def _append_table(lines: list[str], headers: tuple[str, ...], rows: list[tuple[str, ...]]) -> None:
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join("---" for _ in headers) + " |")
    lines.extend("| " + " | ".join(_escape_markdown(cell) for cell in row) + " |" for row in rows)
    lines.append("")


def _append_operation(
    lines: list[str],
    path: str,
    method: str,
    operation: Mapping[str, Any],
    inherited_parameters: list[Mapping[str, Any]],
) -> None:
    lines.append(f"## `{method.upper()} {path}`")
    lines.append("")
    if operation.get("summary"):
        lines.append(_escape_markdown(operation["summary"]))
        lines.append("")
    if operation.get("description"):
        lines.append(_escape_markdown(operation["description"]))
        lines.append("")

    metadata = []
    if operation.get("operationId"):
        metadata.append(("Operation ID", f"`{operation['operationId']}`"))
    if operation.get("tags"):
        metadata.append(("Tags", ", ".join(f"`{tag}`" for tag in operation["tags"])))
    metadata.append(("Security", _format_security(operation.get("security"))))
    _append_table(lines, ("Property", "Value"), metadata)

    parameters = [*inherited_parameters, *operation.get("parameters", [])]
    if parameters:
        lines.append("### Parameters")
        lines.append("")
        _append_table(
            lines,
            ("Name", "In", "Required", "Schema", "Description"),
            [
                (
                    f"`{parameter['name']}`",
                    f"`{parameter['in']}`",
                    "Yes" if parameter.get("required") else "No",
                    _format_schema(parameter.get("schema")),
                    parameter.get("description", "—"),
                )
                for parameter in parameters
            ],
        )

    request_body = operation.get("requestBody")
    if request_body:
        lines.append("### Request body")
        lines.append("")
        _append_table(
            lines,
            ("Required", "Content", "Description"),
            [
                (
                    "Yes" if request_body.get("required") else "No",
                    _content_schema(request_body.get("content")),
                    request_body.get("description", "—"),
                )
            ],
        )

    lines.append("### Responses")
    lines.append("")
    _append_table(
        lines,
        ("Status", "Description", "Content"),
        [
            (
                f"`{status}`",
                response.get("description", "—"),
                _content_schema(response.get("content")),
            )
            for status, response in operation.get("responses", {}).items()
        ],
    )


def _append_schema(lines: list[str], name: str, schema: Mapping[str, Any]) -> None:
    lines.append(f"### Schema: {name}")
    lines.append("")
    if schema.get("description"):
        lines.append(_escape_markdown(schema["description"]))
        lines.append("")

    lines.append(f"Type: {_format_schema(schema)}")
    lines.append("")
    properties = schema.get("properties", {})
    if not properties:
        return

    required = set(schema.get("required", []))
    _append_table(
        lines,
        ("Property", "Type", "Required", "Constraints", "Description"),
        [
            (
                f"`{property_name}`",
                _format_schema(property_schema),
                "Yes" if property_name in required else "No",
                _format_constraints(property_schema),
                property_schema.get("description", "—"),
            )
            for property_name, property_schema in properties.items()
        ],
    )


def _build_reference(specification: Mapping[str, Any]) -> str:
    info = specification["info"]
    lines = [
        "---",
        "title: EMS server API",
        "description: Static OpenAPI reference for the standalone Experiment Management Service",
        "tags: [reference, ems, api, openapi]",
        "---",
        "",
        "# EMS server API",
        "",
        "This is a complete static export of the standalone EMS OpenAPI contract. It is bundled with the Hotvect",
        "docsite for convenient, version-matched reference; it does not move server implementation, release, or",
        "deployment ownership from the EMS server repository.",
        "",
        f"- OpenAPI version: `{specification['openapi']}`",
        f"- EMS API version: `{info['version']}`",
        f"- Contract title: `{info['title']}`",
        "- Machine-readable source: [openapi.yaml](openapi.yaml)",
        "",
        "A deployed EMS instance remains authoritative for its own API identity, server URLs, OAuth scopes, and exact",
        "runtime contract. Refresh this static export from the generated EMS `api.yaml` whenever the server API changes:",
        "",
        "```shell",
        "cd python",
        "python scripts/export_ems_openapi_docs.py /path/to/ems-server/docs/api.yaml",
        "```",
        "",
        "## Service endpoints",
        "",
    ]

    for path, path_item in specification["paths"].items():
        inherited_parameters = path_item.get("parameters", [])
        for method in HTTP_METHODS:
            operation = path_item.get(method)
            if operation:
                _append_operation(lines, path, method, operation, inherited_parameters)

    lines.extend(["## Schemas", ""])
    for name, schema in specification.get("components", {}).get("schemas", {}).items():
        _append_schema(lines, name, schema)

    security_schemes = specification.get("components", {}).get("securitySchemes", {})
    if security_schemes:
        lines.extend(["## Security schemes", ""])
        for name, scheme in security_schemes.items():
            lines.append(f"### {name}")
            lines.append("")
            lines.append(f"Type: `{scheme['type']}`")
            lines.append("")
            client_credentials = scheme.get("flows", {}).get("clientCredentials")
            if client_credentials:
                lines.append(f"Token URL: `{client_credentials['tokenUrl']}`")
                lines.append("")
                _append_table(
                    lines,
                    ("Scope", "Description"),
                    [
                        (f"`{scope}`", description)
                        for scope, description in client_credentials.get("scopes", {}).items()
                    ],
                )

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Export an EMS OpenAPI document into the Hotvect docsite")
    parser.add_argument("source", type=Path, help="Path to the generated EMS OpenAPI YAML document")
    arguments = parser.parse_args()

    source = arguments.source.resolve()
    source_text = source.read_text(encoding="utf-8")
    specification = yaml.safe_load(source_text)
    if not isinstance(specification, dict) or not {"openapi", "info", "paths"}.issubset(specification):
        raise ValueError(f"{source} is not a complete OpenAPI document")

    OUTPUT_DIRECTORY.mkdir(parents=True, exist_ok=True)
    RAW_SPEC_PATH.write_text(source_text, encoding="utf-8")
    REFERENCE_PATH.write_text(_build_reference(specification), encoding="utf-8")
    print(f"Exported {source} to {REFERENCE_PATH.relative_to(REPOSITORY_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
