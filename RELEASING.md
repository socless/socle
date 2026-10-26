# Releasing SoCLess

This document describes how to cut a release. A release publishes:

- `io.socless::socless:<version>` - runtime library (Scala 2.13 / Chisel)
- `io.socless::mill-socless:<version>` - Mill plugin trait (Scala 3 / Mill APIs)
- a GitHub release `v<version>` with the distributable files:
  - `socless-<version>.jar` - standalone UI server + CLI (`java -jar socless-<version>.jar serve`, see [docs/usage-standalone.md](docs/usage-standalone.md))
  - `socless-lsp-<version>.jar` - the language server
  - `socless-<version>.vsix` - the VS Code extension
  - `SoCLess-<version>.html` - the single-file UI
  - `SHA256SUMS` - checksums of the above

## Versioning

SoCLess follows [Semantic Versioning](https://semver.org/) (SemVer):

- **PATCH** (`0.1.x`) - backwards-compatible bug fixes, documentation, or
  dependency bumps that do not change the JSON schema or plugin API.
- **MINOR** (`0.x.0`) - backwards-compatible new features (new schema fields,
  new plugin tasks, new export capabilities).
- **MAJOR** (`x.0.0`) - breaking changes to the JSON schema contract, the
  plugin API, or the minimum Chisel/Mill version requirements.

The authoritative version string is `V.publishVer` in `build.mill`:

```scala
object V {
  val publishVer = "0.1.0-SNAPSHOT"   // <-- bump here
}
```

Between releases the value carries a `-SNAPSHOT` suffix. There is no
automated Maven snapshot publishing today: CI runs `publishLocal` for both
artifacts on every push (so publish metadata cannot rot).

## Automation

- CI (pull requests and pushes to `main`) does not build the distributable
  files. To try a change's vsix or jars, run the Release workflow by hand on
  its branch (Actions, Release, Run workflow, or
  `gh workflow run release.yml --ref <branch>`): it builds them with
  `.github/workflows/build-artifacts.yml` and uploads them as the
  `socless-dist` workflow artifact, kept for a few days. Doing this before
  tagging also catches a packaging break before the release run.
- Pushing a `v<version>` tag runs `.github/workflows/release.yml`: it checks
  that the tag equals `V.publishVer` and that `CHANGELOG.md` has a section for
  the version, builds the files with the same reusable workflow, and publishes
  the GitHub release with that section as its notes. It never overwrites an
  existing release. A manual run (`workflow_dispatch`) only checks and builds.

Preview a version's release notes locally with:

```bash
tools/release/notes.sh 0.2.0
```

## Pre-release checklist

1. Confirm `./mill socless.test` passes on a clean checkout of `main`:

   ```bash
   ./mill socless.test
   ```

2. Confirm the bundled examples still elaborate correctly:

   ```bash
   ./mill example.soclessRun
   ./mill example.soclessRunGcd
   ./mill example.soclessRunPipeline
   ```

3. Collect notable changes from the git log since the last tag:

   ```bash
   git log v0.1.0..HEAD --oneline
   ```

## Release steps

### 1. Bump the version in `build.mill`

Open `build.mill` and change `V.publishVer` from the current `-SNAPSHOT` to the
release version (no suffix):

```scala
val publishVer = "0.2.0"
```

### 2. Add the CHANGELOG section

Add a section for the version to `CHANGELOG.md` (Keep a Changelog style). The
release notes are its text, from the `## [0.2.0]` (or `## 0.2.0`) heading to the
next `## ` heading; the release run fails without it. Check it with
`tools/release/notes.sh 0.2.0`.

### 3. Commit the release to `main`

```bash
git add build.mill CHANGELOG.md
git commit -m "chore: release 0.2.0"
```

### 4. Tag the release

Use an annotated tag:

```bash
git tag -a v0.2.0 -m "Release 0.2.0"
```

### 5. Push the commit and the tag

```bash
git push origin main
git push origin v0.2.0
```

The tag starts the release workflow, which builds the files and publishes the
GitHub release `v0.2.0` ("SoCLess 0.2.0") with them. Follow it in the Actions
tab; if it fails (for example a tag that does not match `V.publishVer`, or a
missing CHANGELOG section), fix the cause, then delete and re-push the tag.

### 6. Publish the Maven artifacts (if needed)

The Maven artifacts are not published by the workflow. If you need them,
publish from a clean checkout of the tag:

```bash
./mill socless.publishLocal
./mill mill-socless.publishLocal
```

Replace `publishLocal` with `publish` (and supply credentials) to push to a
remote repository.

### 7. Bump to the next snapshot

Immediately after tagging, open `build.mill` again and set the next development
version:

```scala
val publishVer = "0.2.1-SNAPSHOT"
```

Commit and push:

```bash
git add build.mill
git commit -m "chore: begin 0.2.1-SNAPSHOT"
git push origin main
```

## Dependency updates

### GitHub Actions

[Dependabot](https://docs.github.com/en/code-security/dependabot) is configured
in `.github/dependabot.yml` to open weekly PRs that bump GitHub Actions version
pins. These PRs should be reviewed and merged promptly.

### Scala / Mill dependencies

Dependabot does not support the Mill build tool natively. For Scala dependency
updates, [Scala Steward](https://github.com/scala-steward-org/scala-steward) is
the recommended tool. To enable it, add this repository to a Scala Steward
instance (or use the community-hosted instance at
`https://github.com/scala-steward-org/scala-steward`) by following the
instructions in the Scala Steward documentation. Scala Steward opens PRs that
update `build.mill` dependency version strings automatically.

Key dependencies to watch for updates:

| Dependency | Current version | Location in `build.mill` |
|------------|-----------------|--------------------------|
| Chisel     | 7.16.0          | `V.chisel`               |
| Mill       | 1.1.6           | `V.mill`                 |
| upickle    | 4.0.2           | `V.upickle`              |
| os-lib     | 0.11.4          | `V.osLib`                |
| ELK        | 0.9.1           | `V.elk`                  |
| utest      | 0.8.5           | `V.utest`                |
