# FlexiData builds and releases

## Source baseline and preserved history

The source was restored on 7 November 2026 to the complete tree from
`eddie-energy/eddie` main at `23f5003e97f007035c3ca5f726e5a7ab6f5c4687`.
The restoration commit is `4ee8b8e9b9e1fe497f9a7713d9cc2b7afe3a0ab4`.
It has the previous FlexiData main and that upstream commit as parents.
The previous fork tip, `93ba6a12ff6682d5c4b25ecdc037bafd3b1cbe3b`, is also
preserved on `codex/archive-pre-reset-2026-11-07`. Existing tags and releases
remain available. CI adaptations follow the restoration in separate commits.

## Build and test

The **Build and test** workflow runs on pull requests and pushes to `main`.
It can also be started through **Actions > Build and test > Run workflow**.
It uses GitHub hosted Ubuntu 24.04 runners, Temurin Java 21 and the checked in
Gradle wrapper. The tasks match upstream: `build integrationTest codeCoverageReport`.
Docker is provided by the hosted runner for Testcontainers. CI sets
`TZ=Europe/Vienna` because upstream AT EDA fixtures depend on the system timezone.
The first hosted run exposed three failures under UTC; application code and test
assertions are preserved. Gradle continues independent tasks after a failure so
reports cover the remaining modules while the workflow still fails. Gradle wrapper
validation and caching use the pinned upstream Gradle action. Reports are
uploaded for successful and failed runs and retained for 14 days.

Main branch builds then run Gradle SonarScanner against FlexiData SonarQube and
wait for the quality gate. Pull requests run the build and tests without receiving
the analysis token. SonarQube Community Build supports main branch analysis.

Repository configuration:

| Setting | Value |
| --- | --- |
| Variable `SONAR_HOST_URL` | `https://sonar.flexidata.at` |
| Variable `SONAR_PROJECT_KEY` | `flexidata-at_eddie` |
| Secret `SONAR_TOKEN` | Project analysis token for this project |

The initial token expires on **7 November 2027**. Replace `SONAR_TOKEN` with a new
project analysis token before that date. Credential values belong in GitHub
Actions secrets. The SonarQube project is private and uses the default Sonar way
quality gate. No upstream SonarCloud account or organization is required.

The upstream Playwright suite remains in **Run playwright E2E tests** as an opt in
manual workflow. It requires `E2E_ENABLED=true`, a runner matching
`E2E_RUNNER_LABELS` (JSON, default `["self-hosted", "flexidata-e2e"]`), connector
credentials and Docker networking that resolves the compose service names from
the test runner. Its required secrets are listed in the workflow. It has not been
configured as part of the standard build. Gradle skips these browser tests unless
`-Prun-e2e-tests` is supplied. Unit and integration tests still run in normal CI.

## Publish a snapshot or release

Open **Actions > Publish containers > Run workflow** and select `main`.
The workflow pins all jobs to the commit selected when the run starts. It runs
the reusable build, integration tests, coverage and SonarQube quality gate before
publishing. Publishing has separate package write permissions. It never runs
automatically on a source push or a Git tag.

For a snapshot, select `snapshot`, leave **version** empty and leave **latest**
unchecked. Images receive `snapshot-<full commit SHA>`. No Git tag or GitHub
release is created.

For a release, select `release` and enter an unused version such as `v1.2.3` or
`v1.2.3-rc.1`. Build metadata is excluded because `+` is invalid in container tags.
Prereleases cannot update `latest`. After all images have been published, the
workflow records a GitHub release with tag `flexidata-v1.2.3`, pointing at the
tested commit. This prefix keeps fork releases separate from upstream tags and
the existing component release tags. Existing image tags and release tags cannot
be overwritten by this workflow.

| Image | Platforms |
| --- | --- |
| `ghcr.io/flexidata-at/valandex-eddie` | linux/amd64 |
| `ghcr.io/flexidata-at/valandex-aiida` | linux/amd64, linux/arm64 |
| `ghcr.io/flexidata-at/eddie-example-app` | linux/amd64 |

The two existing application image names are preserved for deployment consumers.
EDDIE and the example application use the upstream Dockerfiles. AIIDA uses the
upstream Jib configuration and is built once per publication. Image source and
revision labels identify this repository and the tested commit. Published digests
are recorded in the Actions summary. **latest** defaults to false and is promoted
only after all versioned images have been pushed. A publication does not deploy
any application.

A failed push can leave some versioned images present. Inspect the failed run
before retrying. The workflow rejects existing tags, so choose a new release
version or have a package administrator remove only the incomplete publication.
If images were published but GitHub release creation failed, record the release
manually at the run's exact source commit rather than rebuilding the images.
The workflow token needs package write access to existing packages; if a package
does not inherit repository permissions, add this repository under its
**Manage Actions access** settings. New packages are associated using the image
source label.

## Changes from the previous pipelines

The previous fork had two manual component release workflows without a test gate.
The upstream pipeline adds build and test coverage, but relies on upstream runner
labels, SonarCloud, Maven Central credentials and SSH deployments to eddie.energy.
Its automatic tag and workflow completion release chain can build a different
checkout from the triggering tag and creates the release before images succeed.
Its composite action checks out sources again and passes an invalid Docker login
username. The FlexiData workflows pin the tested source, use the actual GitHub
actor for registry authentication, publish only after verification and record the
release after successful publication.

Automatic version bumping, duplicate snapshot publishing, Maven publication and
SSH deployment of upstream documentation and CIM schemas have been removed from
this fork's automation. Their original definitions remain in the upstream
baseline commit. Commit format checking runs on a hosted runner. Link checking
is manual and does not post PR comments. Dependabot checks pinned GitHub Actions
monthly. Fork maintainers are listed in CODEOWNERS.
