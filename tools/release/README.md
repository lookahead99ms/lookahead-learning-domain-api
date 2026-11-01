# Release and deploy pipeline of this application

This directory and the workflows under `.github/workflows/` are identical in the Domain, Web and
Gateway/BFF repositories apart from the app name (`domain`, `web`, `gateway-bff`), which every
tool derives from the repository or the build file and nobody types, and apart from the two files
under `deployment/dev/` that describe this application's own DEV service stack.

## Numbered entries (platform standard, as in Content and Infra)

| Entry | File | What it runs |
| --- | --- | --- |
| `01 · Full` | `release.yml` | on every merge to `main` (unless paused, below), and by hand with no inputs (the same as a merge, from the main head): Security gate → Release tag → Build → Approve ECR publish → Publish to ECR → Deploy to DEV (calls `deploy-dev.yml` with the tag it cut; two approvals in one run) → Record release. Set the repository variable `FULL_DEPLOYS_DEV` to `false` to stop at publication. |
| `02 · Build` | `build.yml` | `release.yml` with `action: build`: cut the next tag at the main head and build; no approval, no credentials |
| `03 · Publish` | `publish.yml` | `release.yml` with `action: publish`: cut the next tag at the main head, build, scan, owner approval, publish to ECR |
| `04 · Deploy DEV` | `deploy-dev.yml` | its own workflow, dispatched by hand for a tag already in ECR (redeploys, rollbacks, `desired_count` 0 to park) or called by `01 · Full` after publication: Security gate → Plan DEV deploy → Approve DEV deploy → Deploy to DEV (one change set, ECS rolling update) → Record deploy (DLV-968) |

`release.yml` is what `02` and `03` call through `workflow_call` (`secrets: inherit`), so the run
keeps the caller's path; `owner_approval.PUBLISHERS` registers `release.yml` and `publish.yml` as
this repository's publisher paths, and `deploy.WORKFLOW` names `deploy-dev.yml` as the only deploy
path. Every run refuses any ref but `main`, runs first-attempt only, and the tooling always
executes from the main commit (`workflowSha`, M) while the application is built from the commit
the release tag resolves to (`sourceSha`, S).

**Merge pause.** With the repository variable `RELEASE_ON_MERGE` set to `false`, a merge to `main`
leaves `01 · Full` skipped at its first job: no tag, no build, no approval wait, no ECR push, no
Actions minutes. Manual `01`, `02`, `03` and `04` are unaffected. Delete the variable or set it to
`true` to resume merge-driven publication. Images are published only when a deploy is wanted.

## Stages of `01 · Full`

1. **Security gate** (`validate`): the release and deploy tooling's own tests with at least 85 percent coverage.
2. **Release tag** (`release`): `pipeline_tag.py cut` creates the next UTC calendar tag
   `vYYYY.MM.DD.HH.NN` at the run commit through the GitHub API (`contents: write` in this job
   only; an existing tag naming the commit is reused, a name another run claimed fails, nothing is
   ever moved). `release_version.py resolve` then freezes `{selector, sourceSha, tagObjectSha,
   mainSha, workflowSha, repository, runId}` as `release-source.json` (artifact `release-source`).
3. **Build** (`build`): `ci.yml` through `workflow_call` with `source-sha` = S and image export:
   every CI gate unchanged, the image built with `SOURCE_REVISION=S`, and the scanned archive
   retained as `release-image-<S>` with a `lookahead-scanned-image/v3` receipt that records S and M.
4. **Approve ECR publish** (`approve`): no credentials, no environment. `owner_release.py gate`
   re-resolves the tag from the frozen record and `owner_release.py approve` opens the owner issue
   bound to the exact image: `APPROVE IMAGE <version>` (the ECR image tag, for example
   `web-v2026.10.28.15.01`) within 40 minutes, `REJECT` stops; edited comments, a moved tag or main,
   or a changed issue fail closed.
5. **Publish to ECR** (`publish`, environment `dev`, no required reviewers): rechecks the live
   approval and the tag before OIDC, imports the scanned image without rebuilding, assumes the
   publisher role for one 15-minute ECR-only session (`image-publish-<run>-1`), pushes once to the
   immutable `lookahead-dev-ecr` tag `<app>-<release>`, reads the digest back and retains
   `registry-image-<S>` and `image-publication-outcome`.
6. **Record release** (`record`): one `release-record-<tag>` artifact per run with the tag, source,
   image digest and owner approval (absent stages recorded as absent).

## Stages of `04 · Deploy DEV`

Since decision V (2026-11-01) each application is one ECS service, `lookahead-dev-<app>`, in one
CloudFormation stack of the same name that this repository owns, described by
`deployment/dev/service.template.json` (ECS task definition and ECS service; parameters
`ApplicationImage`, `DesiredCount`, roles, secret version, discovery or target group) and
`deployment/dev/deploy-config.json` (schema `lookahead-app-deploy-config/v2`: cluster, stack, log
group, Cloud Map service for Web and Domain or target group for the GW-BFF, the observation window).
A deploy is an ECS rolling update: ECS starts the new task beside the old one and drains the old
one once the new one is healthy; the deployment circuit breaker (`Rollback: true`) returns the
service to the previous revision by itself when the new revision never becomes healthy.

Inputs: `release_version` (a tag already in ECR) and `desired_count` (`1` serves, the default;
`0` parks the service at zero tasks while keeping the stack, service and task definition, for the
scheduled scale-down from 2026-11-05). `01 · Full` always passes `1`.

1. **Security gate** (`validate`): the same tests and coverage gate.
2. **Plan DEV deploy** (`plan-dev`, environment `dev`): `release_version.py resolve` freezes the
   dispatched tag; the plan session `<app>-deploy-plan-<run>-1` (15 minutes) runs `deploy.py
   plan`, which reads the digest back from the immutable ECR tag, describes the stack (absent,
   updatable, or refused), renders the parameters and the resource preview, records the image and
   count currently serving, and writes `plan.json` with its digest (artifact `deploy-plan`). A plan
   whose image and count already match the stack is recorded as `unchanged`.
3. **Approve DEV deploy** (`approve-deploy`): no credentials; `deploy.py approve` opens the owner
   issue bound to that plan: `APPROVE DEPLOY <image tag>` (for example `web-v2026.11.01.14.00`)
   within 40 minutes, `REJECT` stops; the plan digest is in the issue body and the receipt.
4. **Deploy to DEV** (`deploy-dev`, environment `dev`): `deploy.py verify` rechecks the approval,
   the plan digest, the template and the configuration before OIDC; the deploy session
   `<app>-deploy-<run>-1` (60 minutes) runs `deploy.py execute`: one change set `app-<run>`
   (CREATE for the first stack, UPDATE afterwards; its preview must show only the task definition
   and the service), execution, then for `desired_count` 1 the ECS rollout `COMPLETED` with exactly
   one `HEALTHY` task carrying the approved image, a clean log window, service discovery listing
   the task (Web, Domain) or healthy target health (GW-BFF), and the observation window (service,
   task and public probe every minute). For `desired_count` 0 it waits until the service runs zero
   tasks. A stack that ends in a rollback state fails the run; the previous revision keeps serving.
   The result is retained whatever happened (`deploy-result`) and the approval issue is closed with
   the outcome.
5. **Record deploy** (`record`): one `deploy-record-<tag>-<run>` artifact bundling plan, approval
   and result.

Wait budgets inside `execute` sum below the 60-minute session (the six roles allow at most 3600 s):
change set 120 s, stack execution 1200 s (ECS health grace is 180 s, the circuit breaker needs a few
failed starts before it rolls back), ECS rollout 300 s, task health 180 s, target health 180 s,
observation 900 s. A stack in `UPDATE_ROLLBACK_COMPLETE` is updatable and is planned normally;
`ROLLBACK_COMPLETE` (a failed first CREATE) is refused because CloudFormation only accepts deletion
there and this tool never deletes: recovery is the owner's
`aws cloudformation delete-stack --stack-name lookahead-dev-<app>` (DeletionPolicy Retain keeps the
service and task definition) followed by a new run.

Rollback is a new run of `04 · Deploy DEV` with the previous tag. Steady state is one task per
application; two run only during one application's rolling update. The GW-BFF reaches Web and Domain
by their stable names `web.lookahead-dev.internal` and `domain.lookahead-dev.internal` (Cloud Map A
records that ECS registers for the running task), so the applications deploy independently; the
only hard dependencies are Domain's database and bucket.

## Settings the repository needs

Environment `dev` allowing exactly the `main` branch and **without** required reviewers (the owner
issue is the approval; a reviewer rule would add GitHub's deployment pop-up on top of it).
Repository variables: `RELEASE_ON_MERGE` (`false` pauses merge-driven publication),
`DEV_PUBLISH_ROLE_ARN` and `DEV_DEPLOY_ROLE_ARN` (optional overrides of `lookahead-dev-deploy`).
No cross-repository secret: the deploy workflows read no other repository (the six-role IAM check is
an Infra concern and runs in Infra workflows only) and the Gateway contract check runs against the
copy committed in this repository (user decisions 2026-11-01). Actions allow-list:
GitHub-owned plus the pinned `aws-actions/configure-aws-credentials`. Issues enabled. The IAM deploy
trust must admit the sessions `<app>-deploy-plan-*` and `<app>-deploy-*` (DLV-968 IAM transition).

## Shared copies and local checks

`release_version.py`, `issue_template.py`, `issue_close.py` and `owner_approval.py` are byte-identical copies of
Infra's `github/releases/release_version.py`, `github/approvals/issue_template.py` and
`github/approvals/image_publication.py`; `owner_release.py`, `candidate.py`, `deploy.py`,
`pipeline_tag.py` and every test are identical across the three app repositories. Update all copies together.


```sh
python3 -m unittest discover -s tools/release -p 'test_*.py'
python3 -P -m coverage run --source=tools/release -m unittest discover -s tools/release -p 'test_*.py'
python3 -P -m coverage report --include='*/release_version.py,*/pipeline_tag.py,*/image.py,*/registry.py,*/issue_template.py,*/owner_approval.py,*/owner_release.py,*/candidate.py,*/issue_close.py,*/deploy.py' --fail-under=85
actionlint .github/workflows/*.yml
```

Tests simulate the GitHub, AWS and Docker boundaries; they do not certify a hosted run. A hosted
run counts only with its receipts: the release-tag record, the registry readback, the deploy plan,
approval and result.
