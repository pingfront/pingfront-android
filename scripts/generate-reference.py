#!/usr/bin/env python3
"""Generates REFERENCE.md from the checked-in API dumps (api/*.api), so the reference never drifts
from the public surface that apiCheck enforces. Run: python3 scripts/generate-reference.py
`--check` writes nothing and fails when REFERENCE.md differs from what the dumps generate (scripts/guard.sh runs it)."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODULES = ["pingfront-core", "pingfront-ui", "pingfront-push", "pingfront-billing", "pingfront-reports", "pingfront"]
ADAPTERS = ["pingfront-revenuecat", "pingfront-superwall", "pingfront-adapty"]
TYPES = {"V": "Unit", "Z": "Boolean", "I": "Int", "J": "Long", "D": "Double", "F": "Float", "B": "Byte", "C": "Char", "S": "Short"}
HIDDEN = re.compile(r"\$\$serializer|\bcomponent\d+\b|\bcopy\$default\b|\bcopy\b|\bhashCode\b|\bequals\b|\btoString\b|access\$|\$default\b|\$annotations\b|<clinit>|DefaultConstructorMarker|-impl\b|box-impl|unbox-impl")


def jtype(desc: str) -> tuple[str, str]:
    arrays = 0
    while desc.startswith("["):
        arrays += 1
        desc = desc[1:]
    if desc[0] == "L":
        end = desc.index(";")
        name = desc[1:end].split("/")[-1].replace("$", ".")
        rest = desc[end + 1:]
    else:
        name = TYPES.get(desc[0], desc[0])
        rest = desc[1:]
    return name + "[]" * arrays, rest


def signature(line: str) -> str | None:
    match = re.match(r"public (?:final |abstract |static |synthetic |bridge |varargs )*(fun|field) (\S+) \((.*?)\)(\S+)|public (?:final |static )*field (\S+) (\S+)", line.strip())
    if not match:
        return None
    if match.group(1) == "fun":
        name, args, ret = re.sub(r"-[A-Za-z0-9_]{7}$", "", match.group(2)), match.group(3), match.group(4)
        params = []
        while args:
            t, args = jtype(args)
            params.append(t)
        rtype, _ = jtype(ret)
        if name == "<init>":
            return f"constructor({', '.join(params)})"
        return f"{name}({', '.join(params)}): {rtype}"
    return None


def main() -> None:
    out = [
        "# pingfront Android SDK: reference",
        "",
        "Every public declaration, generated from the API dumps that `apiCheck` enforces",
        "([scripts/generate-reference.py](scripts/generate-reference.py)). Internal API (`@InternalPingFrontApi`) is excluded. Kotlin",
        "properties appear as their getters. See README.md for usage.",
        "",
    ]
    for module in MODULES + ADAPTERS:
        if module == ADAPTERS[0]:
            out += [
                "## Adapters",
                "",
                "Vendor adapters, one optional artifact each (see README.md, Adapters). Each reports offer slots with its",
                "`source`, opens the vendor's paywall for offer cards, keeps the entitlement current and links identity.",
                "",
            ]
        dump = ROOT / module / "api" / f"{module}.api"
        if not dump.exists():
            continue
        out += [f"## `{module}`", ""]
        klass = None
        members: list[str] = []

        def flush() -> None:
            if klass and not HIDDEN.search(klass):
                out.append(f"### `{klass}`")
                out.append("")
                for m in sorted(set(members)):
                    out.append(f"- `{m}`")
                out.append("")

        for line in dump.read_text().splitlines():
            head = re.match(r"public (?:final |abstract )*(?:interface |annotation )*class (\S+)", line)
            if head:
                flush()
                klass = head.group(1).replace("/", ".").replace("$", ".")
                members = []
            elif line.strip() and line.strip() != "}":
                sig = signature(line)
                if sig and not HIDDEN.search(sig):
                    members.append(sig)
        flush()
    text = "\n".join(out).rstrip() + "\n"
    target = ROOT / "REFERENCE.md"
    if "--check" in sys.argv:
        if not target.exists() or target.read_text() != text:
            print("REFERENCE.md is stale: run python3 scripts/generate-reference.py after ./gradlew apiDump")
            sys.exit(1)
        return
    target.write_text(text)


if __name__ == "__main__":
    main()
