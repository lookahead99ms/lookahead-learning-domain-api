#!/usr/bin/env python3
"""Record release stage of `01 · Full`: one release record per run.

Tag-driven. Reads the frozen release-source record of this run (RELEASE_SOURCE_RECORD)
and never a typed commit. Mode:

  record     one release record per run: tag, source, image digest, and the publication
  and owner-approval receipts
             (a receipt the run did not produce is recorded as absent)

The DEV deploy has its own tools and its own record (`.github/pipeline/deploy/`,
artifact `deploy-record-<tag>-<run>`). Identical copy in the Domain, Web and Gateway/BFF
repositories. Never calls AWS or GitHub.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys

# ../shared holds the modules every pipeline step imports
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "shared"))
import owner_approval as approval

SCHEMA_RECORD = "app-release-record/v1"
# `app-release-record/v1` has carried these since its first run. Only stages this
# pipeline no longer has could fill them (candidate verification, the keep-or-rollback
# decision, the selection of another repository's tooling); the workflow never passed
# them, so a run records them as null, with `devDecision` null beside them. They stay so
# the record keeps the shape its earlier copies have.
RETIRED_RECEIPTS = ("verification", "decision", "infraTooling")
MAX_BODY = 2 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write(path, value, *, new=True):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x" if new else "w") as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write("\n")


def releaseRecord(env):
    return approval.releaseRecord(env)


def readJson(path, limit=MAX_BODY):
    path = Path(path)
    require(
        path.is_file() and path.stat().st_size <= limit,
        "Receipt missing or exceeds bound: " + path.name,
    )
    return json.loads(path.read_text(), object_pairs_hook=approval.unique)


def publishedDigest(bound):
    """The manifest digest ECR returned at publish: `registry.bind` records it as
    `image` = `<repository>@sha256:<hex>`."""
    image = bound.get("image")
    if isinstance(image, dict):  # older shape: {'imageDigest': ...}
        image = image.get("imageDigest", "")
    digest = str(image or "").rsplit("@", 1)[-1]
    return digest if re.fullmatch("sha256:[a-f0-9]{64}", digest) else ""


def record(env, paths, now=None):
    """Everything this run proved, in one file; absent optional receipts are recorded as
    absent, never invented."""
    release = releaseRecord(env)
    result = {
        "schema": SCHEMA_RECORD,
        "app": release["app"],
        "releaseVersion": release["releaseVersion"],
        "imageTag": release["imageTag"],
        "sourceSha": release["sourceSha"],
        "workflowSha": release["workflowSha"],
        "repository": release["repository"],
        "runId": release["runId"],
        "recordedAt": (now or datetime.now(timezone.utc))
        .astimezone(timezone.utc)
        .isoformat(),
        "receipts": {},
    }
    for name, path in paths.items():
        require(
            name not in RETIRED_RECEIPTS,
            "Receipt " + name + " belongs to a stage this pipeline no longer has",
        )
        path = Path(path) if path else None
        if path is None or not path.is_file():
            result["receipts"][name] = None
            continue
        data = readJson(path)
        require(
            not any(
                k in data and data[k] != release[k]
                for k in ("releaseVersion", "sourceSha", "runId")
                if k in release
            ),
            "Receipt " + name + " belongs to another release or run",
        )
        result["receipts"][name] = data
    for name in RETIRED_RECEIPTS:
        result["receipts"][name] = None
    bound = result["receipts"].get("registryImage") or {}
    result["imageDigest"] = publishedDigest(bound) or None
    result["devDecision"] = None
    return result


def main(argv=None, env=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["record"])
    parser.add_argument("--output", help="record to write (must not exist)")
    parser.add_argument("--registry-image", help="registry-image.json of this run")
    parser.add_argument("--approval", help="owner-approval.json of this run")
    args = parser.parse_args(argv)
    require(bool(args.output), "--output path required")
    result = record(
        env, {"registryImage": args.registry_image, "approval": args.approval}
    )
    write(args.output, result)
    print(
        json.dumps({k: v for k, v in result.items() if k != "receipts"}, sort_keys=True)
    )
    return result


if __name__ == "__main__":
    main()
