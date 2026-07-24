# SavePoint Radio - Dev Server Panel (sow-003)

A single-file, stdlib-only Python web panel for running a local Minecraft Fabric
dedicated server during mod development. It lives entirely under
`tools/server-panel/` and manages a server instance in `<repo>/.server/`.

Server target: **Minecraft 26.2 / Fabric Loader 0.19.3**.

## Prerequisites

- Python 3.12 (WSL side).
- Temurin JDK 25 installed WSL-side at `~/.jdks/jdk-25.0.3+9`:

  ```bash
  mkdir -p ~/.jdks && \
  curl -L 'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse' \
    -o /tmp/temurin25.tar.gz && \
  tar -xzf /tmp/temurin25.tar.gz -C ~/.jdks
  ```

  If your extracted directory name differs, update `java_bin` in `config.json`.

## Usage

```bash
cd tools/server-panel

python3 panel.py --doctor   # check java, server jar, EULA state (exit 0/1)
python3 panel.py --setup    # create .server/, .server/mods/, download the Fabric server jar (idempotent)
python3 panel.py            # run the panel
```

Then open <http://127.0.0.1:8750> - from the Windows browser is fine, WSL2
forwards localhost automatically.

## EULA

The panel **never auto-accepts** the Minecraft EULA. First start performs a
"eula-gate boot": the server writes `.server/eula.txt` (with `eula=false`) and
exits. The panel then shows an EULA section with a link to
<https://aka.ms/MinecraftEULA>; only an explicit click on
"I accept the Minecraft EULA" rewrites the file to `eula=true`.

## Mods

Drop the Simple Voice Chat jar (and any other dev-time mods) into
`.server/mods/` and restart the server from the panel.

## Deploying the mod

Placeholder: the `deployMod` Gradle task that builds this repo's mod and copies
it into `.server/mods/` arrives with **sow-001 Phase 1**. Until then, copy the
built jar into `.server/mods/` by hand.

## Security

The panel binds to `127.0.0.1` only (hard-coded, not configurable) and has no
authentication. It is a local dev tool - do not port-forward or expose it.

Two browser-level guards protect the localhost-only bind:

- Every request must carry a `Host` header of exactly `127.0.0.1:8750` or
  `localhost:8750` (DNS-rebinding protection).
- POST requests with an `Origin` header must match
  `http://127.0.0.1:8750` / `http://localhost:8750` (CSRF protection -
  a malicious web page cannot drive `/start`, `/stop`, or `/accept-eula`).
  Requests without an `Origin` header (curl, scripts) are allowed.

If you change `panel_port` in `config.json`, both checks follow the new port.
