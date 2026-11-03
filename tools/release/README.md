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
| `05 · Recover DEV` | `recover-dev.yml` | dispatched by hand when a deploy left the service unhealthy or its stack stuck: Security gate → Plan DEV recovery → Approve DEV recovery (one approval) → Recover DEV (park, target, and only if needed park again, fallback, park) → Record recovery (DLV-969) |

`release.yml` is what `02` and `03` call through `workflow_call` (`secrets: inherit`), so the run
keeps the caller's path; `owner_approval.PUBLISHERS` registers `release.yml` and `publish.yml` as
this repository's publisher paths, and `deploy.DEPLOY_WORKFLOWS` names `deploy-dev.yml` (and its two callers) and
`recover-dev.yml` as the only deploy paths. Every run refuses any ref but `main`, runs first-attempt only, and the tooling always
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
   updatable, busy but parkable, or refused), renders the parameters and the resource preview, records the image and
   count currently serving, and writes `plan.json` with its digest (artifact `deploy-plan`). A plan
   whose image and count already match the stack is recorded as `unchanged` (a duplicate image) and offers
   `refresh` and `verify` instead of `deploy` (`verify` only when the duplicate is a parked service).
3. **Approve DEV deploy** (`approve-deploy`): no credentials; `deploy.py approve` opens the owner
   issue bound to that plan: `APPROVE DEPLOY <image tag>` (for example `web-v2026.11.01.14.00`)
   within 40 minutes, `REJECT` stops; the plan digest is in the issue body and the receipt. For a
   **duplicate image** the issue title and first line carry a warning, the window is **5 minutes**, and
   the answers are `APPROVE REFRESH <image tag>` (restart on the same image: the change set sets the
   template parameter `RefreshToken` to `run-<run id>`, CloudFormation registers a new task definition
   revision and ECS performs the rolling update; the new task re-reads its configuration and, for Domain,
   the content release in S3), `APPROVE VERIFY <image tag>` (no AWS change, verification only, no
   observation) or `REJECT`; no answer closes the issue with nothing done (user decision 2026-11-02).
   The receipt records the chosen mode and the deploy job executes only that mode.
4. **Deploy to DEV** (`deploy-dev`, environment `dev`): `deploy.py verify` rechecks the approval,
   the plan digest, the template and the configuration before OIDC; the deploy session
   `<app>-deploy-<run>-1` (60 minutes) runs `deploy.py execute`: one change set `app-<run>`
   (CREATE for the first stack, UPDATE afterwards; its preview must show only the task definition
   and the service), execution, then for `desired_count` 1 the ECS rollout `COMPLETED` with exactly
   one `HEALTHY` task whose container reports the approved image digest (`describe-tasks`; the deploy
   session is denied `DescribeTaskDefinition` by the Infra bundle and does not need it), a clean log window, service discovery listing
   the task (Web, Domain) or healthy target health (GW-BFF), and the observation window (service,
   task and public probe every minute: 15 minutes after a deploy, `refreshObservationMinutes`, 5, after
   a refresh, none after a verify). Each recorded step and each observation round prints one line to
   the job log, so the long waits are visible. For `desired_count` 0 it waits until the service runs zero
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

**A stack stuck in an update or rollback** (DLV-969, user decision 2026-11-02: recovery is two pipeline runs, never
the AWS CLI). When no revision can become healthy (for example both the new and the previous one fail readiness),
CloudFormation keeps the stack in `UPDATE_IN_PROGRESS` or `UPDATE_ROLLBACK_IN_PROGRESS` for a long time and refuses
every change set, and the circuit breaker's rollback relaunches the failing revision. Recover with:

1. `04 · Deploy DEV` with any published `release_version` and `desired_count` **0**. On a busy stack the plan is
   marked `busyStackPark` and the approval issue says so. The deploy session sets the ECS service to DesiredCount 0
   directly (`ecs update-service`), so the stuck rollout stops and ECS reports the service stable; it waits at most
   30 minutes for CloudFormation to finish its update or rollback, setting DesiredCount 0 again if the rollback puts
   1 back; then the usual park change set records DesiredCount 0 in the stack, and the run verifies zero tasks.
   `UPDATE_ROLLBACK_FAILED` or the 30-minute limit stops the run (break-glass runbook on the Admin page Support
   runbooks).
2. `04 · Deploy DEV` with the fixed `release_version` and `desired_count` **1**: an ordinary rolling update.

A deploy with `desired_count` 1 on a busy stack is refused with that instruction; any other busy or failed stack
state is refused as before. The direct scale-down needs `ecs:UpdateService` on this application's service for the
`<app>-deploy-*` sessions; until that IAM transition (DLV-969 proposal, owned by the Infra IAM lane) is installed
the call is denied, the run fails at that step and nothing else changes. Wait budget of a busy-stack park: settle
1800 s, change set 120 s, stack execution 1200 s, scale-in 300 s, inside the 60-minute session.

Rollback is a new run of `04 · Deploy DEV` with the previous tag. Steady state is one task per
application; two run only during one application's rolling update. The GW-BFF reaches Web and Domain
by their stable names `web.lookahead-dev.internal` and `domain.lookahead-dev.internal` (Cloud Map A
records that ECS registers for the running task), so the applications deploy independently; the
only hard dependencies are Domain's database and bucket.

## Stages of `05 · Recover DEV`

Use it instead of a sequence of `04 · Deploy DEV` runs when a service does not serve and you want one run that ends either
serving a release or parked, with no AWS CLI (DLV-969, user decision 2026-11-02). Inputs: `release_version` (the release
to serve), `fallback_release` (optional) and `healthy_minutes` (10, 15, 20 or 30; default 15). It shares the `deploy-dev`
concurrency group, so it never runs beside a deploy. Use `04 · Deploy DEV` for ordinary deploys, rollbacks and parks.

1. **Security gate**: the release tooling tests with the 85 percent gate, now including `recover.py`.
2. **Plan DEV recovery** (`plan-recovery`, environment `dev`): `release_version.py resolve` freezes the target tag;
   `recover.py fallback` chooses the fallback: the `fallback_release` input, or else the newest release whose deploy
   verified healthy on `main` (a successful `04 · Deploy DEV` run with `desired 1`, or a successful `01 · Full` / `03 ·
   Publish` run whose `Deploy to DEV` job succeeded, read from its `deploy-record-<release>-<run>` artifact name; never
   the target itself). With neither, the plan stops and asks for the input. The fallback tag is frozen the same way;
   then `recover.py plan` writes one `deploy.py` plan per stage (artifact `recover-plan`).
3. **Approve DEV recovery**: one issue lists every stage, both releases and the park steps; the owner answers
   `APPROVE RECOVER <app>-<tag>` within 40 minutes (`REJECT` stops the run).
4. **Recover DEV** (environment `dev`): the live approval is rechecked before any credentials; then each stage assumes
   its own deploy session `<app>-deploy-<run>-<n>` (60 minutes each) and runs `recover.py stage`:
   - `park` (session 1): DesiredCount 0 for the target release. On a stack in `UPDATE_IN_PROGRESS` /
     `UPDATE_ROLLBACK_IN_PROGRESS` it uses the busy-stack park above (direct scale-down, wait, park change set).
   - `target` (2): the target release at DesiredCount 1; its stack may take at most `healthy_minutes`, then the usual
     verification and a short observation (the refresh window).
   - `park-again` (3) and `fallback` (4): only when the target did not serve; the fallback release the same way.
   - `park-final` (5): only when the fallback did not serve either; the run then fails.
   A serving stage that ran its change set and did not verify counts as not serving and the sequence continues. A park
   that fails, a stack in any other state (for example `UPDATE_ROLLBACK_FAILED`), or a failure before a change set (wrong
   session, preview mismatch) stops the run. The summary step writes a table of every stage (release, DesiredCount,
   outcome, task definition revision, times, stop reasons of the newest stopped tasks) to the run summary and fails the
   run unless a release serves; the stage results are kept (artifact `recover-result`) and the issue is closed with the
   outcome.
5. **Record recovery**: one `recover-record-<release>-<run>` artifact per run.

Each stage keeps every `deploy.py` check: provenance, owner on `main`, the frozen tag, the published digest, the stage
plan's digest and template, the change-set preview, and the session identity. Stage change sets are named
`app-<run>-<stage>`. Prerequisite: the busy-stack park's `ecs:UpdateService` for the `<app>-deploy-*` sessions (above);
without it a busy stack stops the run at its first stage, and an idle stack recovers without it.

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

`recover.py` and `test_recover.py` are identical across the three app repositories too; `recover-dev.yml` differs only in the
application name (as `deploy-dev.yml` does). `release_version.py`, `issue_template.py`, `issue_close.py` and `owner_approval.py` are byte-identical copies of
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
