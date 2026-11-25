# Linux MCP Bridge — Architecture Proposal

Status: design-only preview; no MCP server is present in this repository yet.

## 1. Repository audit (master snapshot)

- Repository: `zskbot/cxxx` (fork of `phisher98/CXXX`), default branch: `master`.
- Snapshot inspected: `7383b4e70c9253a526962195f87550bffee4de7d`.
- Recursive Git tree: 929 entries (537 directories, 392 files).
- Kotlin source files: 199.
- Gradle module build files: 89.
- Root build: Gradle Kotlin DSL, Android Library modules, Cloudstream Gradle plugin.
- Root Android configuration: compile SDK 36, min SDK 21; Java toolchain 17; Kotlin JVM target 11.
- `settings.gradle.kts` dynamically includes each top-level directory containing `build.gradle.kts`.
- No MCP / Model Context Protocol implementation or server reference was found by repository code search.
- Existing workflows: `.github/workflows/build.yml` and `.github/workflows/pull_request.yml`. They build Cloudstream artifacts; the push workflow also writes to the `builds` branch. They are not part of this proposal and must not be used for validation.

## 2. Decision: separate Linux process, not an Android Gradle module

Do not add MCP dependencies to the root Android/Kotlin build. Add a standalone Node.js 20+ service under `tools/linux-mcp-bridge/` in a later implementation PR. This keeps the Android plugin graph unchanged and avoids Gradle discovering the bridge as a Cloudstream module (the directory must not contain a `build.gradle.kts`).

Recommended implementation stack:

- Node.js 20+ (ES modules).
- Official `@modelcontextprotocol/sdk` package, pinned through a lockfile.
- stdio transport by default; no listening TCP/HTTP port.
- Read-only access to a configured local clone of this repository.
- Unit tests using Node's built-in `node:test`.

## 3. Initial MCP contract (read-only)

| Tool | Input | Output / limits |
|---|---|---|
| `health` | none | Node version, Linux platform, resolved project root, bridge version |
| `list_modules` | optional name filter | Top-level Gradle modules only; name and build-file presence |
| `inspect_module` | module name | Sanitized Gradle coordinates/config summary, manifest path, Kotlin entrypoint file paths |
| `project_summary` | none | Gradle wrapper version, root build metadata, module/file counts |

The server must not return arbitrary file contents by default. Any future source-reading tool needs an explicit path allowlist, strict size cap, and separate review.

## 4. Security boundaries

The bridge is a project-inspection adapter, not a browser automation agent or media extractor.

- No shell / subprocess execution, command runner, arbitrary file read/write, or repository mutation tools.
- No outbound network requests and no provider-site access.
- No playback, extraction, scraping, download, or resolution of media URLs.
- No arbitrary URL, path, or module filesystem path supplied by a caller.
- Resolve module names only from discovered top-level directories that contain `build.gradle.kts`; reject traversal, symlinks escaping the repository root, and hidden paths.
- Run as an unprivileged Linux user with read-only repository permissions.
- Keep stdio as the only default transport. If a remote PC Connect gateway is later required, put it in a separate adapter with authenticated, encrypted transport, explicit tool allowlists, and no public unauthenticated listener.
- Never store credentials or tokens in this repository or MCP tool arguments.

The repository's GPL-3.0 license covers repository code. Its README's DMCA disclaimer is not evidence that third-party media is licensed for download or redistribution. This bridge deliberately exposes metadata only.

## 5. Linux implementation layout (next PR)

```text
tools/linux-mcp-bridge/
  package.json
  package-lock.json
  README.md
  src/
    index.mjs          # MCP stdio server and tool registration
    project.mjs        # constrained read-only project discovery
  test/
    project.test.mjs   # path traversal, symlink, module discovery tests
```

Suggested local workflow after the implementation exists:

```bash
node --version
java -version
./gradlew --version
cd tools/linux-mcp-bridge
npm ci
npm test
npm start
```

These commands are a proposed PC Connect Linux verification sequence, not tests already executed.

## 6. PC Connect integration model

The MCP process must run on the Linux machine that owns the checkout. A ChatGPT session cannot reach a process merely because it exists on a remote PC. PC Connect must provide a supported, authenticated MCP handoff/tunnel or a local MCP host must launch the stdio process. Verify the actual PC Connect connection and transport before configuring client-side registration. Do not publish a raw unauthenticated MCP port.

## 7. Scope and acceptance criteria

- Separate Linux MCP process; root Android Gradle project remains unchanged.
- Four read-only tools listed above.
- No shell, media, provider-network, credential, or write capabilities.
- Tests cover module discovery, invalid module names, path traversal, and symlink escape.
- Manual build/test on the user's connected PC Connect Linux only.
- No GitHub Actions, no Production deployment, no merge, and no changes to `master`.

## 8. Audit limitations

This is a static repository metadata audit using the Git tree, root Gradle configuration, README, and workflow files. It is not a complete semantic review of all 199 Kotlin files, a dependency vulnerability scan, or a successful Android build. Those require a checked-out workspace and execution on the designated Linux machine.
