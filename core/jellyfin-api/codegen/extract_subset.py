#!/usr/bin/env python3
"""Extract the transitive closure of schemas needed by `jellyfin-api`'s
frozen interface (see ../src/lib.rs) from the Jellyfin OpenAPI doc into a
standalone JSON Schema document that `cargo-typify` can consume (rewrites
'#/components/schemas/X' refs to '#/$defs/X').

Graduated from spikes/s3-openapi/extract_subset.py (see SPIKE.md there for
the full rationale). Two changes from the spike:

  - ROOTS is extended to cover everything W1 needs: full BaseItemDto browse
    fields (pulled automatically, it's a root type), MediaStream, the
    DeviceProfile family (DirectPlayProfile/TranscodingProfile/
    SubtitleProfile/CodecProfile all come in transitively via DeviceProfile
    refs), UserItemDataDto, QuickConnectResult, trickplay metadata
    (TrickplayInfoDto), and the WebSocket event payload shapes
    (LibraryUpdateInfo, UserDataChangeInfo).
  - W1.3: added MediaSegmentDtoQueryResult (GET /MediaSegments/{itemId}
    response wrapper), which pulls in MediaSegmentDto -> MediaSegmentType
    transitively.
  - additionalProperties:false stripping (the load-bearing fix, see below)
    is unchanged and remains a hard requirement.

This keeps the generated crate small while still trivially adjustable: add
a type to ROOTS and rerun to pull in more of the API surface, or pass
--full to skip filtering and emit all schemas.
"""
import argparse
import json

SRC = "jellyfin-openapi-stable.json"

ROOTS = [
    "BaseItemDto",
    "BaseItemDtoQueryResult",  # wraps GET /Items, /UserViews, /UserItems/Resume, /Shows/NextUp
    "AuthenticationResult",
    "AuthenticateUserByName",  # POST /Users/AuthenticateByName request body
    "UserDto",
    "SessionInfoDto",
    "PlaybackInfoDto",  # POST /Items/{itemId}/PlaybackInfo request body
    "PlaybackInfoResponse",
    "MediaSourceInfo",
    "MediaStream",
    "DeviceProfile",  # pulls DirectPlayProfile/TranscodingProfile/SubtitleProfile/CodecProfile
    "QuickConnectResult",
    "QuickConnectDto",
    "UserItemDataDto",
    "TrickplayInfoDto",
    "LibraryUpdateInfo",  # WebSocket LibraryChanged event payload
    "UserDataChangeInfo",  # WebSocket UserDataChanged event payload
    "MediaSegmentDtoQueryResult",  # wraps GET /MediaSegments/{itemId}; pulls in MediaSegmentDto -> MediaSegmentType
]

# Response-side fields the spec marks `required` that a real server can omit
# without the payload being otherwise malformed: relax these to optional so
# one missing field doesn't fail deserializing the whole object (and, for a
# list field embedded in a page, the whole page). Keyed by schema name ->
# field names to drop from that schema's `required` list. Deliberately
# excludes `QuickConnectDto.Secret`: that's a request body this client
# builds itself, not a server response, so typify keeping it non-optional
# is the right contract (a caller must supply it).
RELAX_REQUIRED = {
    "UserItemDataDto": ["Key"],
    "UserDataChangeInfo": ["UserDataList"],
    "UserPolicy": ["AuthenticationProviderId", "PasswordResetProviderId"],
}

# BaseItemDto.ImageBlurHashes and BaseItemPerson.ImageBlurHashes are inline
# (non-$ref) object schemas with identical shape (one nullable
# string-to-string map per image type) that only differ in `description`;
# typify synthesizes two field-identical types from them. Extracted into one
# shared named schema and $ref'd from both so a single type is generated.
IMAGE_BLUR_HASHES_HOSTS = ["BaseItemDto", "BaseItemPerson"]


def relax_required(schemas):
    for name, fields in RELAX_REQUIRED.items():
        schema = schemas.get(name)
        if schema is None or "required" not in schema:
            continue
        schema["required"] = [f for f in schema["required"] if f not in fields]
        if not schema["required"]:
            del schema["required"]


def share_image_blur_hashes(schemas):
    host = schemas.get(IMAGE_BLUR_HASHES_HOSTS[0])
    if host is None or "ImageBlurHashes" not in host.get("properties", {}):
        return
    shared = dict(host["properties"]["ImageBlurHashes"])
    shared.pop("description", None)
    schemas["ImageBlurHashes"] = shared
    for name in IMAGE_BLUR_HASHES_HOSTS:
        schema = schemas.get(name)
        if schema is not None and "ImageBlurHashes" in schema.get("properties", {}):
            schema["properties"]["ImageBlurHashes"] = {
                "$ref": "#/components/schemas/ImageBlurHashes"
            }


def find_refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str) and v.startswith("#/components/schemas/"):
                out.add(v.split("/")[-1])
            else:
                find_refs(v, out)
    elif isinstance(node, list):
        for x in node:
            find_refs(x, out)


def rewrite(node):
    """Rewrite $ref targets for the standalone doc, and drop
    'additionalProperties: false'.

    Jellyfin's spec marks almost every object schema 'additionalProperties:
    false'. typify honors that literally and emits
    '#[serde(deny_unknown_fields)]', which is the OPPOSITE of what we need:
    a hard requirement here is tolerating unknown fields from server drift
    (older/newer Jellyfin servers, custom builds, future spec additions).
    So this generator intentionally treats the spec's 'closed object' intent
    as advisory only and always allows unknown fields at deserialization
    time. See spikes/s3-openapi/SPIKE.md for the full rationale.
    """
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str) and v.startswith("#/components/schemas/"):
                out[k] = v.replace("#/components/schemas/", "#/$defs/")
            elif k == "additionalProperties" and v is False:
                continue  # drop it; absence means "unknown fields allowed"
            else:
                out[k] = rewrite(v)
        return out
    elif isinstance(node, list):
        return [rewrite(x) for x in node]
    else:
        return node


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", action="store_true", help="emit all schemas, no filtering")
    ap.add_argument("-o", "--output", default="jellyfin-schemas-subset.json")
    args = ap.parse_args()

    with open(SRC) as f:
        spec = json.load(f)
    schemas = spec["components"]["schemas"]
    relax_required(schemas)
    share_image_blur_hashes(schemas)

    if args.full:
        selected = schemas
    else:
        visited = set()
        stack = list(ROOTS)
        missing = []
        while stack:
            name = stack.pop()
            if name in visited:
                continue
            visited.add(name)
            s = schemas.get(name)
            if s is None:
                missing.append(name)
                continue
            refs = set()
            find_refs(s, refs)
            for r in refs:
                if r not in visited:
                    stack.append(r)
        if missing:
            print("WARNING: root types not found in spec:", missing)
        selected = {k: schemas[k] for k in visited if k in schemas}

    # `visited` is a set (and `find_refs` collects into sets too), so the traversal order --
    # and therefore dict insertion order above -- varies across runs with Python's per-process
    # string hash randomization. Sort by schema name so `$defs` (and `--full`'s output) come out
    # in the same order every run; the subset file is a checked-in artifact and needs to diff
    # cleanly.
    selected = dict(sorted(selected.items()))

    rewritten = rewrite(selected)
    doc = {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "title": "JellyfinSchemas",
        "$defs": rewritten,
    }
    with open(args.output, "w") as f:
        json.dump(doc, f, indent=2)
    print(f"Wrote {args.output} with {len(rewritten)} schemas (of {len(schemas)} total)")


if __name__ == "__main__":
    main()
