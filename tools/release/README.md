# Release and deploy pipeline of this application

This directory and the workflows under `.github/workflows/` are identical in the Domain, Web and
Gateway/BFF repositories apart from the app name (`domain`, `web`, `gateway-bff`), which every
tool derives from the repository or the build file and nobody types, and apart from the two files
under `deployment/dev/` that describe this application's own DEV slot stacks.

## Numbered entries (platform standard, as in Content and Infra)

| Entry | File | What it runs |
| --- | --- | --- |
| `01 · Full` | `release.yml` | on every merge to `main` (unless paused, below), and by hand with no inputs (the same as a merge, from the main head): Security gate → Release tag → Build → Approve ECR publish → Publish to ECR → Record release. It stops at publication; nothing is deployed. |
| `02 · Build` | `build.yml` | `release.yml` with `action: build`: cut the next tag at the main head and build; no approval, no credentials |
| `03 · Publish` | `publish.yml` | `release.yml` with `action: publish`: cut the next tag at the main head, build, scan, owner approval, publish to ECR |
| `04 · Deploy DEV` | `deploy-dev.yml` | its own workflow, dispatch only, for a tag already in ECR (the only entry that takes a version): Security gate → Plan DEV deploy → Approve DEV deploy → Deploy to DEV → Record deploy (DLV-968) |

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

The deploy is three CloudFormation change sets on stacks this repository owns, driven by
`tools/release/deploy.py` and described by `deployment/dev/service.template.json` (one slot: ECS
task definition and ECS service `lookahead-dev-<app>-<slot>`, parameters `Slot`, `ApplicationImage`,
`DesiredCount`, …) and `deployment/dev/deploy-config.json` (cluster, subnets, security group, roles,
secret ARNs and version ids, Cloud Map names, the flip mode, the observation window).

1. **Security gate** (`validate`): the same tests and coverage gate.
2. **Plan DEV deploy** (`plan-dev`, environment `dev`): `release_version.py resolve` freezes the
   dispatched tag; the plan session `<app>-deploy-plan-<run>-1` (15 minutes) runs `deploy.py
   plan`, which reads the digest back from the immutable ECR tag, finds the serving slot (Cloud Map alias
   instance for Web and Domain, listener `ActiveSlot` for Gateway), picks the idle slot as the
   candidate, renders the parameters and the resource preview, and writes `plan.json` with its
   digest (artifact `deploy-plan`).
3. **Approve DEV deploy** (`approve-deploy`): no credentials; `deploy.py approve` opens the owner
   issue bound to that plan: `APPROVE DEPLOY <image tag>` (for example `domain-v2026.11.01.03.00`) within 40 minutes, `REJECT` stops; the plan digest is in the issue body and the receipt.
4. **Deploy to DEV** (`deploy-dev`, environment `dev`): `deploy.py verify` rechecks the approval,
   the plan digest, the template and the configuration before OIDC; the deploy session
   `<app>-deploy-<run>-1` (60 minutes) runs `deploy.py execute`,
   in order: change set `app-<run>-candidate` (create or update of the candidate slot stack with the
   published image and `DesiredCount` 1; its preview must match the plan), ECS rollout `COMPLETED`
   with the task `HEALTHY`, service discovery listing it, a clean log window and (Gateway) healthy
   target health; on any failure the candidate is set back to `DesiredCount` 0 and nothing is flipped.
   Then the flip (Cloud Map instance `stable` → `<app>-<slot>`, or change set `app-<run>-flip` with
   `ActiveSlot` on the listener stack), the observation window (service, task and public probe every
   minute), and change set `app-<run>-retire` (previous slot to `DesiredCount` 0; the stack, service
   and task definition stay). The result is retained whatever happened (`deploy-result`) and the
   approval issue is closed with the outcome.
5. **Record deploy** (`record`): one `deploy-record-<tag>-<run>` artifact bundling plan, approval
   and result.

Wait budgets inside `execute` sum below the 60-minute session (the six roles allow at most 3600 s):
change set 120 s, stack execution 600 s, ECS rollout 300 s, task health 180 s, target health 180 s,
observation 900 s, flip and retire 120 + 600 s each. A run that stopped after the candidate was
verified but before the flip is repeated with the same tag: the plan records `candidateUpToDate`
and skips the candidate change set. A slot stack in `UPDATE_ROLLBACK_COMPLETE` is still updatable
and is planned normally; `ROLLBACK_COMPLETE` (a failed first CREATE) is refused because
CloudFormation only accepts deletion there and this tool never deletes: recovery is the owner's
`aws cloudformation delete-stack --stack-name lookahead-dev-<app>-<slot>` (DeletionPolicy Retain
keeps the service and task definition) followed by a new run.

Rollback is a new run of `04 · Deploy DEV` with the previous tag: the previous slot is idle again,
so it becomes the candidate. Steady state is one task per application; two run only during one
application's deploy. Phase 1 coupling (until DLV-968 phase 2): the Gateway reaches
`web-<its own slot>` and `domain-<its own slot>`, so a Web or Domain run leaves the previous slot
running while the Gateway listener still points at it, and a Gateway run requires Web and Domain to
be running in the candidate slot.

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
