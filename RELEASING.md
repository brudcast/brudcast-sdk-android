# Releasing

The SDK is published to [Maven Central](https://central.sonatype.com) as five artifacts under
`com.brudcast`: `sdk-core`, `sdk-inapp`, `sdk-push`, `sdk-push-firebase` and `sdk-bom`. Publishing
a GitHub release runs `.github/workflows/release.yml`, which tests, signs and uploads them to the
Central Portal with the [Gradle Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/).

## Release

1. Set `sdkVersion` in `gradle.properties` to the new version, move the `Unreleased` section of
   `CHANGELOG.md` under that version with today's date, and merge to `main`.
2. Create a GitHub release from `main` with the tag `v<sdkVersion>`, such as `v0.1.0`, and the
   changelog section as its notes. The workflow refuses a tag that does not match `sdkVersion`.
3. The workflow uploads the artifacts to the Central Portal, which validates them: signatures,
   POMs, sources and javadoc. Open [Deployments](https://central.sonatype.com/publishing/deployments),
   check the deployment, and press **Publish**. It appears on Maven Central within about 30
   minutes, and in search later.

Releases on Maven Central cannot be changed or deleted. A mistake needs a new version.

The upload waits for **Publish** because `mavenCentralAutomaticPublishing=false` in
`gradle.properties`. Once releases are routine, set it to `true` and the workflow publishes on its
own.

### Trying the pipeline without releasing

Run the **Release** workflow by hand from the Actions tab (it skips the tag check). Check the
deployment validates in the Portal, then press **Drop** instead of **Publish**.

## Check a publication locally

```sh
./gradlew publishToMavenLocal
scripts/check-publication.sh
```

This publishes to `~/.m2/repository` without signing and checks every file Maven Central needs is
there. CI runs the same check on every pull request. To sign locally as well, set the
`signingInMemoryKey*` properties below; publications are signed only when a key is set.

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

In the repository's **Settings → Environments**, create an environment called `release`. Add
required reviewers to it if a release should need an approval. Add these secrets to it:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | The Portal token's username |
| `MAVEN_CENTRAL_PASSWORD` | The Portal token's password |
| `SIGNING_KEY` | The contents of `signing-key.asc` |
| `SIGNING_KEY_ID` | The key's short id |
| `SIGNING_KEY_PASSWORD` | The key's passphrase |

The workflow checks they are all set before it builds anything.
