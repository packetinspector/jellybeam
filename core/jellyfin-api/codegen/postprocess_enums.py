#!/usr/bin/env python3
"""Post-process cargo-typify's generated `models.rs`: append a
`#[serde(other)]` catch-all `Unknown` variant to every unit-variant,
Deserialize-deriving enum.

Why (W1.1 review, finding B1 -- BLOCKER): the Jellyfin OpenAPI spec models
every string enum (BaseItemKind, MediaStreamType, VideoRangeType, ...) as a
closed set. typify honors that literally: an enum tag the client doesn't
recognize (a newer server version added a BaseItemKind variant, a plugin
introduced a custom one, etc.) fails deserialization of that *one* field
with serde's default externally-tagged behavior -- and because these enums
are almost always embedded in `BaseItemDto`/`ItemsResult`, one drifted
field currently fails the whole page, not just that item.

The fix has to happen at the Rust level, not the schema level: dropping the
`enum` constraint in extract_subset.py (turning it into a plain `string`)
would lose the typed variants entirely, which is strictly worse than an
`Unknown` fallback. So this script runs *after* cargo-typify and rewrites
its output:

  1. For every `pub enum Name { ... }` block whose variants are all
     unit-like (name-only, optionally `#[serde(rename = "...")]`'d) and
     whose derive list includes both `serde::Deserialize` and
     `serde::Serialize`, append:

         #[serde(other)]
         Unrecognized,

     `#[serde(other)]` makes serde fall back to this variant instead of
     erroring when the wire value doesn't match any known tag. It also
     works for `Serialize` (an `Unrecognized` value serializes back out as
     the literal string `"Unrecognized"`, which is fine -- we only ever
     construct it by deserializing, never by hand).

     The sentinel is named `Unrecognized`, not `Unknown`, because 7 of the
     41 enums (CollectionType, ExtraType, MediaType, PersonKind,
     TranscodeReason, VideoRange, VideoRangeType) already have a
     spec-defined variant literally called `Unknown` with its own meaning
     (e.g. "collection type not set") -- reusing that name for "this build
     doesn't recognize the wire value at all" would collide with, and be
     semantically different from, the real spec value. Using one
     consistent sentinel name across all 41 enums (rather than `Unknown`
     for 34 of them and something else for the other 7) keeps the pattern
     uniform for callers.

  2. Enums typify considers "path-safe" also get a hand-written
     `impl Display` with an exhaustive `match *self { ... }` over every
     variant. Adding a variant to the enum without updating that match
     would fail to compile, so this script also appends a
     `Self::Unrecognized => f.write_str("Unrecognized"),` arm to the
     matching `impl ::std::fmt::Display for Name` block, keyed by enum
     name. `FromStr`/`TryFrom` impls are left untouched: they already end
     in a wildcard `_ => Err(...)` arm, so they still compile, and there's
     no requirement that the string `"Unrecognized"` round-trips back
     through `FromStr` (nothing in this crate parses enum values from
     user-supplied strings).

Idempotent: an enum that already has an `Unrecognized` variant (e.g.
because regen.sh was re-run without a clean typify pass) is left alone.

Any enum this script *can't* safely handle (non-unit variants, e.g. a
future spec change turning some enum into a tuple/struct variant carrier)
is skipped and reported at the end -- never mutated blindly. `regen.sh`
treats a non-empty skip list as a hard failure (see its sanity check) so
schema-shape drift here is surfaced instead of silently leaving an enum
without a fallback.
"""
import re
import sys

ENUM_RE = re.compile(
    r"(?P<prefix>#\[derive\(\s*"
    r":: serde :: Deserialize,\s*"
    r":: serde :: Serialize,"
    r"(?:[^)]*?)"
    r"\)\]\s*\n"
    r"pub enum (?P<name>[A-Za-z_][A-Za-z0-9_]*) \{\n)"
    r"(?P<body>.*?)"
    r"(?P<close>\n\})",
    re.S,
)

# A variant line is either a bare unit variant ("Ident,") or an attribute
# line ("#[serde(rename = \"...\")]", "#[serde(other)]", etc.) decorating
# the following one. Blank lines are tolerated.
UNIT_VARIANT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*,$")
ATTR_LINE_RE = re.compile(r"^#\[.*\]$")


def enum_is_unit_only(body: str) -> bool:
    for raw_line in body.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        if ATTR_LINE_RE.match(line):
            continue
        if UNIT_VARIANT_RE.match(line):
            continue
        return False
    return True


def add_unknown_variants(text: str):
    """Returns (new_text, modified_names, skipped_names)."""
    modified = []
    skipped = []

    def repl(m: "re.Match[str]") -> str:
        name = m.group("name")
        body = m.group("body")
        if "Unrecognized," in body or "#[serde(other)]" in body:
            # Already has a fallback (idempotent re-run); leave as-is.
            return m.group(0)
        if not enum_is_unit_only(body):
            skipped.append(name)
            return m.group(0)
        modified.append(name)
        # `body` (captured non-greedily) ends right after the last
        # variant's trailing comma, with no newline -- the newline before
        # the closing brace belongs to `close`. So the insertion supplies
        # its own leading newline and leaves the trailing one to `close`.
        insertion = "\n    #[serde(other)]\n    Unrecognized,"
        return m.group("prefix") + body + insertion + m.group("close")

    new_text = ENUM_RE.sub(repl, text)
    return new_text, modified, skipped


def add_display_arms(text: str, names: list):
    """Append `Self::Unrecognized => f.write_str("Unrecognized"),` to the
    `impl Display` match block for each enum name in `names`. Returns
    (new_text, patched_names) -- an enum with no generated Display impl
    (typify only generates one for some schemas) is simply not patched,
    which is fine since nothing needs to match on it exhaustively there.
    """
    patched = []
    names_set = set(names)

    display_re = re.compile(
        r"(impl ::std::fmt::Display for (?P<name>[A-Za-z_][A-Za-z0-9_]*) \{\n"
        r"    fn fmt\(&self, f: &mut ::std::fmt::Formatter<'_>\) -> ::std::fmt::Result \{\n"
        r"        match \*self \{\n)"
        r"(?P<body>.*?)"
        r"(?P<close>\n        \}\n    \}\n\})",
        re.S,
    )

    def repl(m: "re.Match[str]") -> str:
        name = m.group("name")
        if name not in names_set:
            return m.group(0)
        body = m.group("body")
        if "Self::Unrecognized" in body:
            return m.group(0)
        patched.append(name)
        # Same leading-newline convention as add_unknown_variants: `body`
        # ends right after the last arm's trailing comma, no newline.
        arm = '\n            Self::Unrecognized => f.write_str("Unrecognized"),'
        return m.group(1) + body + arm + m.group("close")

    new_text = display_re.sub(repl, text)
    return new_text, patched


def check_all_have_fallback(text: str):
    """Sanity-check mode (regen.sh's B1 gate): every unit-variant enum that
    derives both Deserialize and Serialize must already carry a
    `#[serde(other)]` fallback arm. Returns a list of enum names missing
    one (empty means the file passes). Does not modify `text`.
    """
    missing = []
    for m in ENUM_RE.finditer(text):
        name = m.group("name")
        body = m.group("body")
        if not enum_is_unit_only(body):
            # Not a plain string-enum this script manages (e.g. gained a
            # tuple/struct variant); B1 doesn't apply the same way, and
            # add_unknown_variants() already reports it under SKIPPED.
            continue
        if "#[serde(other)]" not in body:
            missing.append(name)
    return missing


def main():
    check_mode = "--check" in sys.argv
    args = [a for a in sys.argv[1:] if a != "--check"]
    if len(args) != 1:
        print(f"usage: {sys.argv[0]} [--check] <path-to-models.rs>", file=sys.stderr)
        sys.exit(2)
    path = args[0]
    with open(path) as f:
        text = f.read()

    if check_mode:
        missing = check_all_have_fallback(text)
        if missing:
            print(
                "postprocess_enums --check: FAIL -- unit-variant enum(s) with no "
                "#[serde(other)] fallback (B1 regression): " + ", ".join(sorted(missing)),
                file=sys.stderr,
            )
            sys.exit(1)
        print("postprocess_enums --check: OK -- every unit-variant enum has a #[serde(other)] fallback")
        return

    text, modified, skipped = add_unknown_variants(text)
    text, patched = add_display_arms(text, modified)

    with open(path, "w") as f:
        f.write(text)

    print(f"postprocess_enums: added #[serde(other)] Unrecognized to {len(modified)} enum(s)")
    print(f"postprocess_enums: patched {len(patched)} Display impl(s) for the new variant")
    if skipped:
        print(
            "postprocess_enums: SKIPPED (non-unit-variant, needs manual review): "
            + ", ".join(sorted(skipped)),
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()
