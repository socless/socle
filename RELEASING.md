# Releasing SoCLess

This document describes how to cut a release of the two published artifacts:

- `io.socless::socless:<version>` - runtime library (Scala 2.13 / Chisel)
- `io.socless::mill-socless:<version>` - Mill plugin trait (Scala 3 / Mill APIs)

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

Between releases the value carries a `-SNAPSHOT` suffix. CI publishes snapshots
automatically; only tagged commits become stable releases.

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

3. Review `CHANGELOG.md` (if present) or collect notable changes from the git
   log since the last tag:

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

### 2. Commit the version bump

```bash
git add build.mill
git commit -m "chore: release 0.2.0"
```

### 3. Tag the release

```bash
git tag v0.2.0
```

Use an annotated tag so the GitHub release page gets a sensible title:

```bash
git tag -a v0.2.0 -m "Release 0.2.0"
```

### 4. Push the commit and tag

```bash
git push origin main
git push origin v0.2.0
```

Pushing the `vX.Y.Z` tag triggers the GitHub Actions release workflow (once
configured), which publishes both artifacts to Maven Central / Sonatype.

### 5. Publish locally (manual / fallback)

If CI publishing is not yet wired up, publish from a clean checkout:

```bash
./mill socless.publishLocal
./mill mill-socless.publishLocal
```

Replace `publishLocal` with `publish` (and supply credentials) to push to a
remote repository.

### 6. Bump to the next snapshot

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
| Chisel     | 7.11.0          | `V.chisel`               |
| Mill       | 1.1.6           | `V.mill`                 |
| upickle    | 4.0.2           | `V.upickle`              |
| os-lib     | 0.11.4          | `V.osLib`                |
| ELK        | 0.9.1           | `V.elk`                  |
| utest      | 0.8.5           | `V.utest`                |
