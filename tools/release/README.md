# Release pipeline of this application

This directory and the workflows under `.github/workflows/` are identical in the Domain, Web and
Gateway/BFF repositories apart from the app name (`domain`, `web`, `gateway-bff`), which every
tool derives from the repository or the build file and nobody types.

## Numbered entries (platform standard, as in Content and Infra)

| Entry | File | What it runs |
| --- | --- | --- |
| `01 · Full` | `release.yml` | on every merge to `main`, and by hand: Security gate → Release tag → Build → Approve publication → Publish → Deploy DEV → Verify DEV → Keep or roll back DEV → (Keep DEV or Roll back DEV) → `05 · Deploy PROD` (shown, skipped until PROD exists) → Record the release |
| `02 · Build` | `build.yml` | `release.yml` with `action: build`: cut the next tag at the main head (or use the given tag) and build; no approval, no credentials |
| `03 · Publish` | `publish.yml` | `release.yml` with `action: publish` for an existing tag: build, scan, owner approval, publish without rebuilding in between |
| `04 · Deploy DEV` | `deploy-dev.yml` | `release.yml` with `action: deploy-dev` for an existing published tag: plan, owner approval, deploy of the inactive DEV slot, verification, keep or roll back |

`release.yml` is also what the three entries call through `workflow_call` (`secrets: inherit`), so
the run keeps the caller's path; `owner_approval.PUBLISHERS` registers `release.yml` and
`publish.yml` as this repository's publisher paths. Every run refuses any ref but `main`, runs
first-attempt only, and the tooling always executes from the main commit (`workflowSha`, M) while
the application is built from the commit the release tag resolves to (`sourceSha`, S).

## Stages of `01 · Full`

1. **Security gate** (`validate`): the release tooling's own tests with at least 85 percent coverage.
2. **Release tag** (`release`): on `full`, and on `build` without a tag, `pipeline_tag.py cut` creates
   the next UTC calendar tag `vYYYY.MM.DD.HH.NN` at the run commit through the GitHub API
   (`contents: write` in this job only; an existing tag naming the commit is reused, a name another
   run claimed fails, nothing is ever moved). Every path then runs `release_version.py resolve`
   once and freezes `{selector, sourceSha, tagObjectSha, mainSha, workflowSha, repository, runId}`
   as `release-source.json` (artifact `release-source`). `publish` and `deploy-dev` require a tag.
3. **Build** (`build`): `ci.yml` through `workflow_call` with `source-sha` = S and image export:
   every CI gate unchanged, the image built with `SOURCE_REVISION=S`, and the scanned archive
   retained as `release-image-<S>` with a `lookahead-scanned-image/v3` receipt that records S and M.
4. **Approve publication** (`approve`): no credentials, no environment. `owner_release.py gate`
   re-resolves the tag from the frozen record and `owner_release.py approve` opens the owner issue
   (gist first, then tables) bound to the exact image: `APPROVE IMAGE <digest>` within 40 minutes,
   `REJECT` stops; edited comments, a moved tag or main, or a changed issue fail closed.
5. **Publish** (`publish`, environment `dev`): rechecks the live approval and the tag before OIDC,
   imports the scanned image without rebuilding, assumes the publisher role for one 15-minute
   ECR-only session, pushes once to the immutable `lookahead-dev-ecr` tag `<app>-<release>`, reads
   the digest back and retains `registry-image-<S>` and `image-publication-outcome`.
6. **Deploy DEV** (`deploy-dev`, environment `dev`, held until the repository variable
   `DEV_DEPLOY_ENABLED` is `true`): one run, never a dispatch of another repository. `candidate.py
   infra` selects Infra's newest calendar tag; Infra tooling is checked out at that commit with the
   `CROSS_REPO_READ_TOKEN` secret and runs its own deploy chain (`application_workflow config`,
   `application_inputs`, `plan`, `application_approval approve` with a second owner issue, `verify`,
   `execute`) against the reviewed config in `DEV_DEPLOYMENT_CONFIG_JSON`. The candidate result
   and the deployment inputs are retained.
7. **Verify DEV** (`verify-dev`): `candidate.py verify` proves the Infra result names this tag and
   the digest ECR returned at publish; with `DEV_VERIFY_URL` set, the live candidate must answer
   200 and name the tag. A failed verification rolls back without a question.
8. **Keep or roll back DEV** (`decide-dev`): a third owner issue bound to this run:
   `APPROVE KEEP DEV <digest>` keeps and proceeds, `APPROVE ROLLBACK DEV <digest>` restores the
   previous release, `REJECT` stops with nothing changed; 40-minute window.
9. **Keep DEV** / **Roll back DEV**: the DEV cutover and rollback tools are Infra-owned and not
   available yet (`candidate.py cutover` and `rollback` stop with the reason and change nothing).
10. **05 · Deploy PROD (not configured)**: visible, skipped until `PROD_ENABLED` and a PROD
    account, cluster and environment exist; it never pretends to deploy.
11. **Record the release** (`record`): one `release-record-<tag>` artifact per run with the tag,
    source, image digest, approvals, verification and decision receipts (absent stages recorded as
    absent).

Rollback by tag is a new run of `04 · Deploy DEV` (or `03 · Publish`) with an older tag; S is
older than M, every approval happens again, and the existing ECR tag is never overwritten.

## Settings the repository needs

Environment `dev` allowing exactly the `main` branch, with required reviewers. Repository
variables: `DEV_PUBLISH_ROLE_ARN` (optional override of `lookahead-dev-deploy`),
`DEV_DEPLOY_ENABLED`, `DEV_DEPLOYMENT_CONFIG_JSON`, `DEV_VERIFY_URL`, later `PROD_ENABLED`.
Secret `CROSS_REPO_READ_TOKEN` (read-only on Infra) for the DEV deploy stage. Actions allow-list:
GitHub-owned plus the pinned `aws-actions/configure-aws-credentials`. Issues enabled.

## Shared copies and local checks

`release_version.py`, `issue_template.py` and `owner_approval.py` are byte-identical copies of
Infra's `github/releases/release_version.py`, `github/approvals/issue_template.py` and
`github/approvals/image_publication.py`; `owner_release.py`, `candidate.py`, `pipeline_tag.py`
and every test are identical across the three app repositories. Update all copies together.

```sh
python3 -m unittest discover -s tools/release -p 'test_*.py'
python3 -m coverage run --source=tools/release -m unittest discover -s tools/release -p 'test_*.py'
python3 -m coverage report --include='*/release_version.py,*/pipeline_tag.py,*/image.py,*/registry.py,*/issue_template.py,*/owner_approval.py,*/owner_release.py,*/candidate.py' --fail-under=85
actionlint .github/workflows/*.yml
```

Tests simulate the GitHub, AWS and Docker boundaries; they do not certify a hosted run. A hosted
run counts only with its receipts: the release-tag record, the registry readback, the candidate
result and the release record.
