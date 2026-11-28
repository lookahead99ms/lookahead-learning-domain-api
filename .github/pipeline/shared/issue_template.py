"""Portable approval-issue presentation v3. Rendering only; no approval authority.

Began as a copy of Infra's renderer; this application repository keeps its own copy,
byte-identical in Domain, Web and Gateway/BFF. v2 (2026-10-27): change-first titles, a
headline alert, tables for every section, an explicit "Affected AWS resources" row with
resource type, physical name and action, resource property sub-tables, and GitHub alert
blocks for risks and the decision. v3 (DLV-1027, owner decision 2026-11-27): the title
reads `ENV: <Action> <subject> <version or name>`, the body's first line says in one
sentence what happens after approval, and the command is the readable phrase
`APPROVE <action> <env> <subject> <version or name>` in a copyable block; digests stay
in the body as a record. The seven section names are unchanged.
"""

import html
import json
import re

SECTIONS = (
    "Decision summary",
    "What changes",
    "Validation and source",
    "Risks and recovery",
    "Owner, run and deadlines",
    "Approve or reject",
    "Technical evidence",
)
SUMMARY_FIELDS = (
    "Why this is needed",
    "What you authorize",
    "Impact",
    "If you do not approve",
    "Target",
)
RESOURCE_COLUMNS = (
    ("action", "Action"),
    ("type", "Resource type"),
    ("consoleName", "Name in AWS Console"),
    ("note", "Notes"),
)
NAME_KEYS = ("consoleName", "name", "key")
# DLV-1027 (owner decision 2026-11-27): the approving comment names the change like a
# branch name, `APPROVE <action> <env> <subject> <version or name>`. Action words are
# lower case except fixed names (IAM); the environment is always present, so an
# approval for one environment can never satisfy another; then one to three names
# (subject, version, a mode). Digests and bare code words (`APPROVE IMAGE <tag>`) are
# not commands.
COMMAND = re.compile(
    r"APPROVE (?:(?:[a-z][a-z-]*|IAM) ){1,3}(?:dev|prod)"
    r"(?: [A-Za-z0-9][A-Za-z0-9._-]*){1,3}"
)


def text(value):
    return (
        html.escape(str(value))
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("`", "&#96;")
        .replace("|", "&#124;")
    )


def cell(value):
    """Table cell: keeps reviewed inline Markdown (links, code) but can never break the
    row or table."""
    return str(value).replace("\r", " ").replace("\n", "<br>").replace("|", "&#124;")


def code(value, language="text"):
    # Payload strings must not terminate their own fence or inject another section.
    fence = "`" * max(
        3, 1 + max((len(m[0]) for m in re.finditer(r"`+", value)), default=0)
    )
    return fence + language + "\n" + value + "\n" + fence


def details(label, markdown):
    markdown = re.sub(
        r"(?m)^(#{1,5}) ", lambda m: "#" * min(6, len(m[1]) + 2) + " ", markdown
    )
    return (
        "<details>\n<summary>"
        + text(label)
        + "</summary>\n\n"
        + markdown.strip()
        + "\n\n</details>"
    )


def table(headers, rows):
    """GitHub table; every value passes through cell()."""
    rows = list(rows)
    if not rows:
        return ""
    head = (
        "| "
        + " | ".join(cell(h) for h in headers)
        + " |\n|"
        + "|".join(" --- " for _ in headers)
        + "|\n"
    )
    return head + "".join(
        "| " + " | ".join(cell(c) for c in row) + " |\n" for row in rows
    )


def alert(kind, markdown):
    """GitHub alert block: NOTE, TIP, IMPORTANT, WARNING or CAUTION."""
    if kind not in ("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION"):
        raise ValueError("Unknown alert kind")
    return (
        "> [!"
        + kind
        + "]\n"
        + "\n".join("> " + line for line in markdown.strip().splitlines())
    )


def title(headline):
    """Issue title: `ENV: <Action> <subject> <version or name>` in plain words, with
    an optional ` · <note>` (for example `duplicate image`). The body links the run."""
    env, action, subject, note = (
        text(headline.get(k, "")).strip() for k in ("env", "action", "subject", "note")
    )
    if not all((env, action, subject)):
        raise ValueError("Headline needs env, action and subject")
    return env + ": " + action + " " + subject + (" · " + note if note else "")


def summaryRows(markdown):
    """Read the two-column decision brief into (field, gist) pairs, keeping the brief's
    label."""
    label, rows = "", []
    for line in markdown.strip().splitlines():
        if line.startswith("## Decision summary"):
            label = line.partition(":")[2].strip()
        elif line.startswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if len(cells) == 2 and cells[0] not in ("Question", "---"):
                rows.append((cells[0], cells[1]))
        elif line.strip() and not line.startswith("|"):
            rows.append(("", line.strip()))
    return label, rows


def summary(markdown, resources=None, expires=None):
    """Decision summary as a two-column table; affected AWS resources are always
    stated."""
    label, rows = summaryRows(markdown)
    shown = [(k, v) for k, v in rows if k in SUMMARY_FIELDS]
    notes = [v for k, v in rows if not k]
    if resources is not None:
        shown.append(("Affected AWS resources", affected(resources)))
    if expires:
        shown.append(("Approval deadline (UTC)", text(expires)))
    body = ("**" + label + "**\n\n" if label else "") + (
        "\n".join(notes) + "\n\n" if notes else ""
    )
    if shown:
        body += table(
            ("Item", "Detail"), [("**" + text(k) + "**", v) for k, v in shown]
        )
    return body.strip() or markdown.strip()


def verb(row):
    """Short actions in capitals (CREATE, NO CHANGE); longer phrases capitalize the
    verb: `CREATE only if absent`."""
    action = " ".join(str(row.get("action", "Review")).split())
    words = action.split(" ", 1)
    if len(action.split()) <= 2:
        return action.upper()
    return words[0].upper() + " " + words[1]


def resourceName(row):
    """Physical name as the AWS Console shows it; a logical ID is never presented as
    one."""
    physical = next((str(row[k]) for k in NAME_KEYS if row.get(k)), "")
    if physical:
        return physical
    return "generated by AWS" + (
        " (logical ID " + str(row["logicalId"]) + ")" if row.get("logicalId") else ""
    )


def affected(rows):
    """One line per resource: ACTION type · name. Empty means nothing is created,
    updated or replaced."""
    if not rows:
        return "**None** created, updated or replaced."
    return "<br>".join(
        "**"
        + text(verb(r))
        + "** `"
        + text(r.get("type", "resource"))
        + "` · `"
        + text(resourceName(r))
        + "`"
        for r in rows
    )


def records(rows):
    """Resource table (action, type, console name, notes) plus one property sub-table
    per resource."""
    rows = list(rows)
    if not rows:
        return (
            "No resource rows supplied; review the exact technical evidence before"
            " approving."
        )
    main = table(
        tuple(h for _, h in RESOURCE_COLUMNS),
        [
            (
                verb(r),
                "`" + text(r.get("type", "resource")) + "`",
                "`" + text(resourceName(r)) + "`",
                text(r.get("note", r.get("notes", ""))),
            )
            for r in rows
        ],
    )
    subs = []
    for index, row in enumerate(rows, 1):
        shown = ("action", "note", "notes", "type") + tuple(
            k for k in NAME_KEYS if row.get(k)
        )[:1]
        properties = [(k, v) for k, v in row.items() if k not in shown]
        if properties:
            subs.append(
                details(
                    str(index) + ". " + resourceName(row) + " — properties",
                    table(
                        ("Property", "Value"),
                        [
                            ("**" + text(k) + "**", "`" + text(v) + "`")
                            for k, v in properties
                        ],
                    ),
                )
            )
    return main + ("\n" + "\n\n".join(subs) if subs else "")


def briefField(markdown, name):
    for line in markdown.splitlines():
        cells = [c.strip() for c in line.strip("|").split("|")]
        if line.startswith("|") and len(cells) == 2 and cells[0] == name:
            return cells[1]
    return ""


def render(
    *,
    overview,
    scope,
    evidence,
    risks,
    repository,
    runId,
    owner,
    expires,
    permissionDeadline,
    command,
    payload,
    created=None,
    planDeadline=None,
    headline=None,
    resources=None,
    validation=None,
    gist=None,
    outcome=None,
):
    """`outcome`: one short sentence, the body's first line, saying what happens after
    approval. `gist`: plain-language Markdown (what this run does, in order, and why)
    shown before any table."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Exact repository required for review links")
    if not re.fullmatch(r"[0-9]+", str(runId)) or not re.fullmatch(
        r"[A-Za-z0-9-]+", owner
    ):
        raise ValueError("Exact owner and numeric workflow run required")
    if not command or any(not COMMAND.fullmatch(line) for line in command.split("\n")):
        raise ValueError(
            "Exact approval command `APPROVE <action> <env> <subject> <version or"
            " name>` required"
        )
    run = f"https://github.com/{repository}/actions/runs/{runId}"
    answer = (
        "`" + command + "`"
        if "\n" not in command
        else "the command block, line by line"
    )
    top = (text(outcome).strip() + "\n\n" + code(command) + "\n\n") if outcome else ""
    if headline:
        top += (
            alert(
                "IMPORTANT",
                "**"
                + text(headline["env"])
                + " · "
                + text(headline["action"])
                + " "
                + text(headline["subject"])
                + (" · " + text(headline["note"]) if headline.get("note") else "")
                + "** — "
                + text(headline["change"])
                + "\n\nApprove by **"
                + text(expires)
                + "** · Owner @"
                + owner
                + " · [Run "
                + str(runId)
                + "]("
                + run
                + ") · Comment exactly "
                + answer
                + " or `REJECT`",
            )
            + "\n\n"
        )
    metadata = table(
        ("Item", "Detail"),
        [
            ("**Responsible owner**", f"@{owner}"),
            ("**Workflow run**", f"[Run {runId}]({run})"),
        ]
        + ([("**Created (UTC)**", text(created))] if created else [])
        + [
            ("**Approval deadline (UTC)**", text(expires)),
            ("**Permission deadline (separate)**", text(permissionDeadline)),
        ]
        + ([("**PLAN deadline**", text(planDeadline))] if planDeadline else []),
    )
    checks = [
        (
            "Workflow run",
            (
                f"[Open the run and its saved artifacts]({run}) — a"
                " preview is not proof of execution"
            ),
        )
    ]
    if briefField(overview, "Validation evidence"):
        checks.append(
            ("Validation evidence", briefField(overview, "Validation evidence"))
        )
    checks += [(k, v) for k, v in (validation or [])]
    evidence = table(("Check", "Result"), checks) + "\n" + evidence
    riskLines = [briefField(overview, "Risk and recovery"), risks]
    risks = alert(
        "WARNING",
        "\n".join("- " + line.strip() for line in riskLines if line and line.strip()),
    )
    decision = (
        alert(
            "TIP",
            f"@{owner}: post a **new comment** containing exactly the"
            " block below. Surrounding whitespace is ignored; letters are"
            " case-sensitive: keep every word, space and the version unchanged.",
        )
        + "\n\n"
        + code(command)
        + "\n\n"
        + table(
            ("Decision", "Comment to post", "Effect"),
            [
                (
                    "Approve",
                    "`" + command.replace("\n", "` then `") + "`",
                    "Authorizes this exact plan once",
                ),
                (
                    "Reject",
                    "`REJECT`",
                    "Stops this run; a later run needs a new approval",
                ),
                ("No response", "—", "Approval expires at the deadline"),
            ],
        )
        + "\n"
        + alert(
            "CAUTION",
            "Leave this issue open. Editing or closing the"
            " issue, editing the approval comment, "
            "or changing the preview invalidates approval.",
        )
    )
    scope = (
        "### Affected AWS resources\n\n" + records(resources) + "\n\n"
        if resources
        else ""
    ) + scope
    first = summary(overview, resources, expires)
    if gist:
        first = "### What this run does\n\n" + gist.strip() + "\n\n" + first
    sections = [
        first,
        scope,
        evidence,
        risks,
        metadata,
        decision,
        details(
            "Expand exact plan and verification data",
            code(json.dumps(payload, sort_keys=True, indent=2), "json"),
        ),
    ]
    return (
        top
        + "\n\n---\n\n".join(
            "## " + heading + "\n\n" + body.strip()
            for heading, body in zip(SECTIONS, sections)
        )
        + "\n"
    )
