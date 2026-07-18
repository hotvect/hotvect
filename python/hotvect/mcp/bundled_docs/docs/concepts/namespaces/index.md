---
title: Namespace identity and canonicalization
description: Use stable namespace handles safely in Hotvect feature transformations
tags: [namespaces, architecture, java, advanced]
---

# Namespace identity and canonicalization

Hotvect namespaces identify intermediate values and features. The runtime uses identity-based maps for these handles,
so two objects with the same text are not interchangeable unless they are canonicalized to the same instance.

## Identity contract

| Need | Use | Verify |
| --- | --- | --- |
| Name a feature or intermediate value | An enum constant or `Namespaces.declareNamespace("name")` | Transformer construction accepts the namespace |
| Combine namespace parts | `Namespaces.declareNamespace(nsA, nsB)` or `declareFeatureNamespace(...)` | Reuse the returned object |
| Diagnose an identity collision | `Namespaces.assertCanonical(namespace)` | Error identifies the conflicting name and object |

## Safe default: enum namespaces

Use enum constants for declared namespaces and pass the same constant through feature factories and transformers:

```java
import com.hotvect.api.data.Namespace;

enum ExampleNamespace implements Namespace {
    request_context_key
}
```

`StandardRankingTransformer` validates namespaces during construction. If it sees an unregistered enum constant,
`Namespaces.assertCanonical(...)` auto-registers the enum and then validates its identity. Explicit
`Namespaces.register(RequestNamespace.class)` is therefore not required for ordinary enum use.

Register an enum explicitly at application wiring time only when you need to establish enum ownership **before** any
string-based declaration with the same name. That makes a string-first collision fail early and visibly.

## String and composite namespaces

Use `Namespaces.declareNamespace("name")` when configuration is string-driven, and retain the returned handle:

```java
Namespace group = Namespaces.declareNamespace("document_group");
record.put(group, value);
```

The first declaration owns that textual name. Repeated declarations return the canonical handle. A later enum with the
same name is a configuration collision—not a value-equivalence shortcut—and registration reports the mismatch.

Composite namespaces are also canonicalized:

```java
Namespace joined = Namespaces.declareNamespace(requestNamespace, group);
```

## Rules that prevent identity bugs

- Cache namespace handles in fields/constants; do not repeatedly declare them on a hot request path.
- Use enum constants directly rather than constructing ad-hoc namespace objects.
- Use one declaration convention for a name across a codebase.
- When an algorithm combines code and JSON configuration, establish the intended enum registration before a loader can
  declare the same name as a string.

## What transformer validation catches

`StandardRankingTransformer` checks the namespaces it receives at construction time. It catches noncanonical
objects and name collisions before feature work starts. For enum constants, the validation's auto-registration keeps
older algorithm wiring working; for non-enum objects, declare/register a canonical handle first.

## Testing

Add a focused transformer construction test when introducing new namespace wiring. You can call
`Namespaces.assertCanonical(...)` in test code to pinpoint a collision. `Namespaces.clear()` is an internal,
package-private Hotvect test helper; algorithm projects should not call it.

## See also

- [Develop a Hotvect algorithm](../../guides/develop-algorithms/index.md)
- [Simple ranking transformer](../../guides/simple-ranking-transformer/index.md)
