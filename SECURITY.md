# Security policy

## Supported versions

Only the latest release gets security fixes. Upgrade to it before reporting.

## Reporting a vulnerability

Please report privately, through GitHub's private vulnerability reporting on
this repository: the Security tab, then "Report a vulnerability", or directly
at https://github.com/socless/socle/security/advisories/new.

Do not open a public issue for a vulnerability. Include the Socle version, how
you ran it (Mill plugin, standalone server, language server, which editor), and
the steps or files that reproduce the problem.

Reports are handled on a best-effort basis. There is no guaranteed response or
fix time.

## Scope

In scope:

- **The language server's live panel** (`socle-lsp`, `LiveHttp.scala`). It
  binds `127.0.0.1` on an ephemeral port and serves the UI, `/design.json`,
  `/ide.json`, the current simulation's trace (`/wave/<id>/...`) and the
  vendored Surfer files. It only answers requests whose `Host` is
  `127.0.0.1:<port>` or `localhost:<port>`; `POST /open` and
  `POST /wave/select` also need a JSON content type and, when an `Origin` is
  sent, a same-origin one. A way around these guards, or a way for another site
  to read from or act through the panel, is in scope.
- **The standalone UI server** (`socle.cli.Launcher serve`, also behind the
  Mill tasks `socleUI` and `socleServe`; `Serve.scala`). It binds `127.0.0.1`
  by default, `0.0.0.0` when it detects WSL, or the address given with
  `--host`. It serves the UI, files from `--dir`, and `POST /convert`, which
  takes a CHIRRTL text body (up to 128 MB, counted on the bytes read) and
  returns the converted `design.json`. It only answers requests whose `Host`
  is `localhost`, `127.0.0.1` or `[::1]`, the `--host` address, or under a
  wildcard bind the IP the connection reached, each with the port;
  `POST /convert` also needs, when an `Origin` is sent, a same-origin one.
  Reading files outside `--dir`, making the conversion do more than parse and
  convert its input, or a way around these guards, is in scope. Binding a non-loopback
  address on purpose (`--host 0.0.0.0`) exposes the server to that network;
  that is the documented behaviour, not a vulnerability.
- **The single-file UI** (`socle.html`) loading a `design.json`, whether
  served, dropped onto the page, or produced by `/convert`. A crafted
  `design.json` or `.fir` file that runs script in the page is in scope.
- **The probe Java agent** (`socle-probe`), which runs inside the user's own
  ChiselSim test JVM to capture the elaborated design. A way for it to do more
  than capture the design and write it to the file it is given is in scope.

Not a vulnerability:

- **View design and Simulate run your own build and tests.** The language
  server asks `./mill` for a test's classpath, then forks that test (with the
  probe agent for View design, with waveforms for Simulate). Opening a
  workspace in an editor with Socle and using these actions (or the view's
  rerun after a save) runs that
  workspace's build and test code, as any build tool does. Only use them on
  code you trust.
- The Mill plugin's `socle` task forks a JVM on your project's run classpath
  and elaborates your design: that runs your code too, by design.
