# Domain image publication

The manual `release-image.yml` workflow takes one source selector: an existing UTC
calendar release tag `vYYYY.MM.DD.HH.NN`. Nobody types a commit. The workflow file
and the trusted tooling under `tools/release/` always run from `main` (the executed
workflow commit, `workflowSha`, M). The application is built from the commit the tag
resolves to (`sourceSha`, S). The run has three jobs:

1. `resolve` runs `release_version.py resolve` once: it peels the tag to exactly one
   commit, requires that commit to be an ancestor of (or equal to) the observed `main`
   head, and freezes `{selector, sourceSha, tagObjectSha, mainSha, workflowSha, repository,
   runId}` as `release-source.json` (artifact `release-source`, 21 days). A missing,
   malformed, off-main or ambiguous tag fails here, before any build.
2. `verify` reuses `ci.yml` with `source-sha` = S: CI checks out S, runs every gate
   unchanged, builds with `SOURCE_REVISION=S` (OCI `org.opencontainers.image.revision`),
   and exports the scanned archive as `release-image-<S>` with a
   `lookahead-scanned-image/v3` receipt that records both `revision` (S) and
   `workflowSha` (M, the `ci.yml` actually executed, hashed from M).
3. `publish` checks out the tooling at M, restores the frozen record, checks out S into
   `release-source/` for verification only, and runs `owner_release.py` with
   `--source-root release-source`. `owner_approval.provenance` re-resolves the tag from
   the frozen record (same commit, same tag object, still on `main`); the plan requires
   the archive's `revision` == S and `workflowSha` == M. `release_version.py recheck`
   runs again immediately before the upload credentials. The image is imported without
   rebuilding and verified against the clean S checkout, its Dockerfile and M's `ci.yml`.

The approval issue, `owner-plan.json`, `publication-outcome.json` and
`registry-image.json` (`lookahead-registry-image/v3`) all carry both commits.
The exact target is `lookahead-dev-ecr` with a `domain-<release-version>` tag in account `509614632283`, us-east-2.
Its repository must already exist with immutable tags; the workflow creates none.

Rollback is a new tag at an older `main` commit: S is older than M, the build, scan
and owner approval happen again, and the existing ECR tag is never overwritten. A
tag that moves between resolve and publish, or a `main` that moves during the run,
stops the run before credentials. A release tag older than this tooling fails safely:
its checkout has no `--source-root`/`workflowSha` contract, so `image.py` at M rejects
the receipt and nothing is uploaded; create a new tag at a commit that includes it.

The `dev` environment must allow only the `main` branch using one custom branch
rule, with no tag rules. Any existing native environment review still happens
before the job starts. The workflow then creates an issue showing the release tag,
the image source commit S, the workflow source commit M, merged PR context for S,
exact image and archive hashes, role, target, run and recovery limits.
Only the owner may comment `APPROVE IMAGE <digest>` within 40 minutes. `REJECT`,
edited approvals, moved main, altered issue/plan or missing provenance block upload.
This is workflow-enforced owner approval, not an independent reviewer control.

The approved publisher role is assumed only after a fresh approval check, with a
15-minute session policy restricted to the exact Domain repository. The uploader
rechecks live approval before push, rejects an existing or uncertain app-prefixed calendar tag,
verifies the loaded image configuration, records intent, pushes once and binds
the ECR manifest/configuration to the tested archive. It does not run SQL or deploy.

After a timeout or failed readback/artifact upload, retain `image-publication-outcome`
and inspect the exact tag before any new run. ECR may have accepted an upload even
when the job failed. Never automatically retry, overwrite, delete or rebuild.
A failed overall run is not a trusted publication for downstream database jobs.

The portable `owner_approval.py` is maintained from Infra's
`github/approvals/image_publication.py`; keep both copies and paired tests identical.
No private Infra checkout is required by this public repository. The older
`registry.validateApproval` function remains available for existing callers;
this workflow uses the explicit owner flow rather than silently weakening that
independent-review helper. `registry.bind` remains the remote identity verifier.

CI uses Python 3.14 and a hash-locked coverage dependency. Local checks:

```sh
python3 -m unittest discover -s tools/release -p 'test_*.py'
python3 -m coverage run --source=tools/release -m unittest discover -s tools/release -p 'test_*.py'
python3 -m coverage report --include='*/issue_template.py,*/owner_approval.py,*/owner_release.py,*/release_version.py,*/image.py,*/registry.py' --fail-under=85
```

`tools/release/release_version.py` is a byte-identical copy of Infra's
`github/releases/release_version.py` (Web and Gateway carry the same copy). Keep all
four and `test_release_version.py` identical apart from the import line.

Tests simulate GitHub/AWS/Docker boundaries and verify rejection and upload
ambiguity. They do not certify live IAM, ECR publication or application deployment.

## Shared approval page

Image approval uses the common seven sections: Decision summary; What changes;
Validation and source; Risks and recovery; Owner, run and deadlines; Approve or
reject; Technical evidence. Native headings, separators, vertical resource details
and expandable evidence use GitHub's own responsive theme. The exact command is
alone in its copyable block.

`issue_template.py` is a byte-identical copy of Infra's
`github/approvals/issue_template.py`. Update Infra, Domain and Content copies and
pinned presentation tests together. Presentation does not change commands,
digests, owner/provenance/expiry checks or authorize editing an active issue.


Approval receipt creation and expiry use whole-second UTC precision to match
GitHub issue/comment timestamps. The runner may otherwise record fractions of a
second and incorrectly reject a newly created issue from that same second as older
than the receipt. Precision is aligned before rendering; the 40-minute lifetime
is not extended, and exact body, owner, source, tag, run and expiry checks remain.
An issue creation time outside the review window has its own diagnostic.

A run that fails at this approval check has not reached AWS credentials or upload.
Preserve its evidence; merge the corrected publisher to `main`, create a new release
tag at the `main` commit you want built, and start a fresh manual run with that tag.
Do not edit the old issue, move a tag to a different commit, reuse an approval or use
GitHub Re-run.
