# Synthetic Platform fixtures

These fixtures contain invented metadata and schedule inputs, with no curriculum
bodies or credentials. `catalog.json` is the trusted catalog for an isolated
Platform test runtime. Its digest covers deterministic JSON without the
`catalogVersion` property (sorted keys and compact separators).
`generated-plan.json` supplies a matching synthetic plan for contract checks.

The three-application HTTP probe belongs to the sibling shared build repository.
From `lookahead-learning-backend`, after Infrastructure has started the isolated
candidate and mounted this catalog:

```sh
python3 verification/probe_backend_apps.py \
  --client-secret-file /path/to/candidate-secrets/oauth-client-secret \
  --verifier-secret-file /path/to/candidate-secrets/identity-verifier-secret \
  --password-file /path/to/candidate-secrets/seed-password \
  --output .codex-scratch/backend-apps-live-probe.json
```

The probe defaults to Gateway 4350, Identity 4351 and Platform 4352 on loopback,
uses synthetic identities, and keeps tokens and cookies in memory. It checks
registration/sign-in, OAuth/PKCE, revocation, author and learner ownership,
plan retries/revisions/deletion and safe support rejection. It deletes its own
completed test plan and attempts session/token cleanup even on failure.
Synthetic registration accounts remain; no account-delete API is implemented.
No valid support feedback or email is submitted.

Infrastructure owns the separate coordinated service/database failure driver.
The probe does not stop services, copy existing account data or certify backup
and restore behavior. See `lookahead-learning-backend/verification/README.md`
for scope and configuration. Earlier `tools/accounts/verify_api.py` continuation
instructions belong to the retained combined API repository, not this application.
