# Releasing

The SDK is published to [Maven Central](https://central.sonatype.com) as five artifacts under
`com.brudcast`: `sdk-core`, `sdk-inapp`, `sdk-push`, `sdk-push-firebase` and `sdk-bom`. Publishing
a GitHub release runs `.github/workflows/release.yml`, which releases them in stages.

## The stages

```
Verify ─▶ Test ─▶ Build and sign ─▶ Upload and validate ─▶ Publish to Maven Central
                                            │                    (waits for approval)
                                            └──────────────▶ Drop the deployment
                                                   (if publish is rejected or fails, or on a dry run)
```

| Stage | What it does | Secrets |
| --- | --- | --- |
| Verify | Reads `sdkVersion`, checks a release's tag is `v<sdkVersion>` and that `CHANGELOG.md` and the README name the same version, and refuses a release while the repository is private | none |
| Test | Unit tests, the release build and the sample app's lint | none |
| Build and sign | Builds and signs the artifacts into `build/staging-repository` with Gradle, checks every file has a signature and checksums, and keeps the zipped bundle for 7 days | the signing key |
| Upload and validate | Uploads the bundle to the Central Portal and waits until the Portal has validated it. The run summary shows the deployment id | the Portal token |
| Publish to Maven Central | After approval in the `maven-central` environment, publishes the deployment and waits until it is on Maven Central | the Portal token |
| Drop the deployment | Removes the deployment from the Portal | the Portal token |

Gradle (with the [Gradle Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/))
builds and signs; `scripts/central-portal.sh` does the uploading, waiting, publishing and dropping
through the Portal's publisher API. A failed stage can be re-run on its own from the run page:
**Upload and validate** re-uploads the stored bundle without rebuilding it.

## Release

1. On a `docs/release-<version>` branch, set `sdkVersion` in `gradle.properties` to the new
   version, move the `Unreleased` section of `CHANGELOG.md` under that version with today's date,
   and set the BOM version in the README's install section to it. Commit as
   `docs: release <version>` and merge to `main`. CI runs `scripts/check-version.sh`, which fails
   until all three name the same version.
2. Create a GitHub release from `main` with the tag `v<sdkVersion>`, such as `v0.1.0`, and the
   changelog section as its notes.
3. The run stops at **Publish to Maven Central** and waits for a reviewer. Open the run, check the
   **Upload and validate** summary, and choose **Review deployments**:
   - **Approve** to publish. The artifacts reach Maven Central within about 30 minutes, and search
     later.
   - **Reject** to stop. **Drop the deployment** then removes it from the Portal.

Releases on Maven Central cannot be changed or deleted. A mistake needs a new version.

## Conventions

- Commits follow [Conventional Commits](https://www.conventionalcommits.org). A change that breaks
  the public API gets a `!` after its type, such as `fix!:`, before 1.0 too.
- `CHANGELOG.md` lists what an app using the SDK gets. How the SDK is built, tested and released
  belongs in the pull request and in this file, not in the changelog.

## Dry run

Run the **Release** workflow by hand from the Actions tab with **dry run** ticked, which is the
default. It goes through every stage up to validation on the Portal, then drops the deployment
instead of publishing it. Nothing is released and nothing is left behind in the Portal.

## Check a publication locally

```sh
./gradlew publishToMavenLocal
scripts/check-publication.sh
```

This publishes to `~/.m2/repository` without signing and checks every file Maven Central needs is
there. CI runs the same check on every pull request. To check a signed publication as the release
does, set the `signingInMemoryKey*` properties below, then:

```sh
./gradlew publishAllPublicationsToStagingRepository
scripts/check-publication.sh --signed build/staging-repository
```

## One-time setup

### 1. The namespace

1. Sign in at [central.sonatype.com](https://central.sonatype.com).
2. Under **Namespaces**, add `com.brudcast`. The Portal shows a verification key.
3. Add it to the `brudcast.com` DNS as a TXT record, then press **Verify** in the Portal. The record
   can go once the namespace is verified.

### 2. The Portal token

Under **Account**, **Generate User Token**. Its username and password are the
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` secrets. The token can be revoked and
regenerated there.

### 3. The signing key

Central requires every file to be signed with a key whose public half is on a public keyserver.

```sh
gpg --quick-gen-key "Brudcast Android SDK <security@brudcast.com>" ed25519 sign 3y
gpg --list-keys --keyid-format short "Brudcast Android SDK"
```

The short id is the 8 characters after `ed25519/`. Publish the public key, then export the private
key for CI:

```sh
gpg --keyserver keyserver.ubuntu.com --send-keys <short id>
gpg --keyserver keys.openpgp.org --send-keys <short id>
gpg --armor --export-secret-keys <short id> > signing-key.asc
```

`keys.openpgp.org` emails a link to confirm the address before it shows the key. Keep the key and
its passphrase in the team's password manager, and delete `signing-key.asc` once it is in GitHub.
Before the key expires, extend it with `gpg --quick-set-expire` and send it to the keyservers again.

### 4. The GitHub secrets

In the repository's **Settings → Secrets and variables → Actions**, add these repository secrets.
Each stage is given only the ones it needs:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | The Portal token's username |
| `MAVEN_CENTRAL_PASSWORD` | The Portal token's password |
| `SIGNING_KEY` | The contents of `signing-key.asc` |
| `SIGNING_KEY_ID` | The key's short id |
| `SIGNING_KEY_PASSWORD` | The key's passphrase |

**Build and sign** checks the signing secrets are set before it builds anything.

### 5. The approval gate

In **Settings → Environments**, create an environment called `maven-central` and add everyone who
may approve a release as **required reviewers**. Under **Deployment branches and tags**, allow only
tags matching `v*`, so **Publish to Maven Central** can run only from a release tag and never from a
branch, even in a run started by hand with dry run unticked. On GitHub's free plan, required reviewers only
work in public repositories, which is one reason **Verify** refuses a release while the repository
is private. Dry runs work either way.
