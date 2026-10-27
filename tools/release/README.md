# Domain image publication

The manual `release-image.yml` workflow runs CI once and imports the same-run
scanned Linux AMD64 archive without rebuilding. `owner_release.py` verifies the
clean source, image archive and configuration before requesting owner approval.
The exact target is `lookahead-dev-ecr` with a `domain-<release-version>` tag in account `509614632283`, us-east-2.
Its repository must already exist with immutable tags; the workflow creates none.

The `dev` environment must allow only the `main` branch using one custom branch
rule, with no tag rules. Any existing native environment review still happens
before the job starts. The workflow then creates an issue showing source/merged
PR context, exact image and archive hashes, role, target, run and recovery limits.
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
python3 -m coverage report --include='*/issue_template.py,*/owner_approval.py,*/owner_release.py' --fail-under=85
```

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
Preserve its evidence; merge the corrected publisher, create a new release tag at
the reviewed new main SHA, and start a fresh manual run. Do not edit the old issue,
move its tag to a different commit, reuse its approval or use GitHub Re-run.
