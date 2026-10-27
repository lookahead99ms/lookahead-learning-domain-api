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
python3 -m coverage report --include='*/owner_approval.py,*/owner_release.py' --fail-under=85
```

Tests simulate GitHub/AWS/Docker boundaries and verify rejection and upload
ambiguity. They do not certify live IAM, ECR publication or application deployment.
