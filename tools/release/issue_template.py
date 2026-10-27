"""Portable approval-issue presentation v1. Rendering only; no approval authority.

Infra owns this file. Content and Domain keep byte-identical portable copies.
"""
import html
import json
import re

SECTIONS = ('Decision summary', 'What changes', 'Validation and source',
            'Risks and recovery', 'Owner, run and deadlines', 'Approve or reject', 'Technical evidence')
SUMMARY_FIELDS = ('Why this is needed', 'What you authorize', 'Impact', 'If you do not approve', 'Target')


def text(value):
    return html.escape(str(value)).replace('\r', ' ').replace('\n', ' ').replace('`', '&#96;').replace('|', '&#124;')


def code(value, language='text'):
    # Payload strings must not terminate their own fence or inject another section.
    fence = '`' * max(3, 1 + max((len(m[0]) for m in re.finditer(r'`+', value)), default=0))
    return fence + language + '\n' + value + '\n' + fence


def details(label, markdown):
    markdown = re.sub(r'(?m)^(#{1,5}) ', lambda m: '#' * min(6, len(m[1]) + 2) + ' ', markdown)
    return '<details>\n<summary>' + text(label) + '</summary>\n\n' + markdown.strip() + '\n\n</details>'


def summary(markdown):
    """Turn the existing two-column decision brief into a readable vertical summary."""
    lines = []
    for line in markdown.strip().splitlines():
        if line.startswith('## Decision summary'):
            label = line.partition(':')[2].strip()
            if label:
                lines += ['**' + label + '**', '']
        elif line.startswith('|'):
            cells = [c.strip() for c in line.strip('|').split('|')]
            if len(cells) == 2 and cells[0] in SUMMARY_FIELDS:
                lines += ['**' + cells[0] + '**', '', cells[1], '']
        else:
            lines.append(line)
    return '\n'.join(lines).strip()


def briefField(markdown, name):
    for line in markdown.splitlines():
        cells = [c.strip() for c in line.strip('|').split('|')]
        if line.startswith('|') and len(cells) == 2 and cells[0] == name:
            return cells[1]
    return ''


def records(rows):
    """Vertical resource cards avoid wide resource tables on narrow screens."""
    cards = []
    for index, row in enumerate(rows, 1):
        fields = '\n'.join('- **' + text(key) + ':** ' + text(value) for key, value in row.items())
        label = row.get('consoleName') or row.get('name') or row.get('key') or row.get('logicalId') or 'Resource'
        cards.append(details(str(index) + '. ' + str(label), fields))
    return '\n\n'.join(cards) or 'No resource rows supplied; review the exact technical evidence before approving.'


def render(*, overview, scope, evidence, risks, repository, runId, owner, expires,
           permissionDeadline, command, payload, created=None, planDeadline=None):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Exact repository required for review links')
    if not re.fullmatch(r'[0-9]+', str(runId)) or not re.fullmatch(r'[A-Za-z0-9-]+', owner):
        raise ValueError('Exact owner and numeric workflow run required')
    digestCommand = r'APPROVE [A-Z][A-Z -]* [0-9a-f]{64}'
    resourceCommand = r'APPROVE APPLY (?:NO_CHANGES|(?:s3|s3-policy|kms|cloudtrail) (?:CREATE|UPDATE) [A-Za-z0-9_./:=-]+)'
    if not command or any(not re.fullmatch(digestCommand + '|' + resourceCommand, line) for line in command.split('\n')):
        raise ValueError('Exact approval command or complete resource approval block required')
    run = f'https://github.com/{repository}/actions/runs/{runId}'
    metadata = [f'- **Responsible owner:** @{owner}', f'- **Workflow run:** [Run {runId}]({run})',
                '- **Approval deadline (UTC):** ' + text(expires),
                '- **Permission deadline (separate):** ' + text(permissionDeadline)]
    if created:
        metadata.append('- **Created (UTC):** ' + text(created))
    if planDeadline:
        metadata.append('- **PLAN deadline:** ' + text(planDeadline))
    evidence = (f'[Open the workflow run and its saved artifacts]({run}). A preview is not proof of execution.\n\n'
                + briefField(overview, 'Validation evidence') + '\n\n' + evidence)
    risks = briefField(overview, 'Risk and recovery') + '\n\n' + risks
    decision = (f'**Approve:** @{owner}, post a new comment containing exactly this approval block:\n\n'
                + code(command) + '\n\nSurrounding whitespace is ignored; keep the command and digest unchanged.\n\n'
                '**Reject:** post a new comment containing `REJECT`.\n\n'
                '> Leave this issue open. Editing or closing the issue, editing the approval comment, '
                'or changing the preview invalidates approval. No response lets this approval expire.')
    sections = [summary(overview), scope, evidence, risks, '\n'.join(metadata), decision,
                details('Expand exact plan and verification data', code(json.dumps(payload, sort_keys=True, indent=2), 'json'))]
    return '\n\n---\n\n'.join('## ' + heading + '\n\n' + body.strip()
                               for heading, body in zip(SECTIONS, sections)) + '\n'
