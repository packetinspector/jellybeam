#!/usr/bin/env python3
"""Post-process cargo-typify's generated `models.rs` for two structural
cleanups that don't belong in extract_subset.py's schema-level scope:

  1. Drop the synthetic `JellyfinSchemas` root-document wrapper type. Every
     typify run over the `$defs`-shaped subset document also emits a
     passthrough `pub struct JellyfinSchemas(pub ::serde_json::Value)` for
     the document's own top-level schema, named from the `"title"`
     extract_subset.py sets on it; nothing in the crate references it.
  2. Collapse a hand-written `impl Default for T` into `#[derive(Default)]`
     on `T` wherever every field initializer is exactly
     `Default::default()`. typify emits the manual impl for every struct
     capable of Default rather than deriving it; a struct with any
     non-default field initializer (e.g. a spec-declared numeric/bool
     default) keeps its manual impl, since `derive(Default)` can't express
     that.

Both run after postprocess_enums.py and before rustfmt; see regen.sh.
"""
import re
import sys

JELLYFIN_SCHEMAS_START = '#[doc = "`JellyfinSchemas`"]'
JELLYFIN_SCHEMAS_END = (
    "impl ::std::convert::From<::serde_json::Value> for JellyfinSchemas {\n"
    "    fn from(value: ::serde_json::Value) -> Self {\n"
    "        Self(value)\n"
    "    }\n"
    "}\n"
)

DEFAULT_IMPL_RE = re.compile(
    r"impl ::std::default::Default for (?P<name>[A-Za-z_][A-Za-z0-9_]*) \{\n"
    r"    fn default\(\) -> Self \{\n"
    r"        Self \{\n"
    r"(?P<body>.*?)\n"
    r"        \}\n"
    r"    \}\n"
    r"\}\n",
    re.S,
)

SIMPLE_FIELD_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*: Default::default\(\),$")


def drop_jellyfin_schemas_type(text: str) -> tuple[str, bool]:
    """Removes the dead `JellyfinSchemas` root wrapper type. A no-op if
    it's already gone (idempotent re-run)."""
    start = text.find(JELLYFIN_SCHEMAS_START)
    if start == -1:
        return text, False
    end_at = text.find(JELLYFIN_SCHEMAS_END, start)
    if end_at == -1:
        raise RuntimeError(
            "postprocess_cleanup: found the JellyfinSchemas doc marker but not "
            "its expected closing impl block; typify's output shape for this "
            "type changed and this script needs a manual look"
        )
    end = end_at + len(JELLYFIN_SCHEMAS_END)
    return text[:start] + text[end:], True


def body_is_all_default(body: str) -> bool:
    for raw_line in body.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        if not SIMPLE_FIELD_RE.match(line):
            return False
    return True


def collapse_trivial_default_impls(text: str) -> tuple[str, list]:
    """Removes each `impl Default for T` block whose body is only
    `field: Default::default(),` lines, and adds `Default` to `T`'s derive
    list. Returns (new_text, names_collapsed)."""
    collapsed = []

    def strip_impl(m: "re.Match[str]") -> str:
        name = m.group("name")
        if not body_is_all_default(m.group("body")):
            return m.group(0)
        collapsed.append(name)
        return ""

    text = DEFAULT_IMPL_RE.sub(strip_impl, text)

    for name in collapsed:
        derive_re = re.compile(
            r"#\[derive\((?P<derives>.*?)\)\]\s*\npub struct " + re.escape(name) + r" \{"
        )

        def add_default_derive(m: "re.Match[str]") -> str:
            derives = [d.strip() for d in m.group("derives").split(",") if d.strip()]
            if "Default" not in derives:
                derives.append("Default")
            return f"#[derive({', '.join(derives)})]\npub struct {name} {{"

        text, n = derive_re.subn(add_default_derive, text, count=1)
        if n == 0:
            raise RuntimeError(
                f"postprocess_cleanup: removed {name}'s manual Default impl but "
                "couldn't find its derive attribute to add Default to"
            )

    return text, collapsed


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <path-to-models.rs>", file=sys.stderr)
        sys.exit(2)
    path = sys.argv[1]
    with open(path) as f:
        text = f.read()

    text, dropped_root_type = drop_jellyfin_schemas_type(text)
    text, collapsed = collapse_trivial_default_impls(text)

    with open(path, "w") as f:
        f.write(text)

    print(
        "postprocess_cleanup: dropped JellyfinSchemas root type: "
        + ("yes" if dropped_root_type else "already gone")
    )
    print(f"postprocess_cleanup: collapsed {len(collapsed)} manual Default impl(s) into derive(Default)")


if __name__ == "__main__":
    main()
