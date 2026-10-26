#!/usr/bin/env python3
"""Make the directory copy of a release jar: the same jar without the windows-amd64 binaries.

Modrinth and CurseForge get this jar; the GitHub release keeps the full one. CurseForge rejects any
jar that contains .exe or .bat files (0.1.5 was rejected on 2026-10-26 for ffmpeg.exe, yt-dlp.exe
and deno.exe), and the owner ruled the same for Modrinth. This copies every entry of the release
jar unchanged except the windows-amd64 folder, and rewrites the bundled THIRD-PARTY-NOTICES.txt so
it lists only what this jar carries. On Windows the mod then finds no bundled set and uses
ffmpeg/yt-dlp from the PATH or from ffmpegBinary/ytdlpBinary, as it does on any platform it ships
no binaries for. The output is deterministic: the same release jar always gives the same bytes.

    python3 tools/linux-only-jar.py build/libs/jockey-radio-0.1.5+build.6.jar
    # writes build/libs/jockey-radio-0.1.5+build.6-linux.jar and prints its checks

Each notice edit must match exactly once, so a reworded notice stops the script instead of
shipping a notice that still names files the jar no longer has.
"""
import hashlib
import os
import sys
import zipfile

WINDOWS_DIR = "jockey_radio/bin/windows-amd64/"
LINUX_DIR = "jockey_radio/bin/linux-amd64/"
NOTICE = "jockey_radio/THIRD-PARTY-NOTICES.txt"
EXE_LIKE = (".exe", ".bat", ".cmd", ".dll", ".msi", ".ps1")

NOTICE_EDITS = [
    ("Prebuilt EXECUTABLES for linux/amd64 and windows/amd64. On first run the mod",
     "Prebuilt EXECUTABLES for linux/amd64. On first run the mod"),
    ("7e93014c5bbb61e7c22afebb478bf92f7a08d59a), both platforms.",
     "7e93014c5bbb61e7c22afebb478bf92f7a08d59a), linux/amd64."),
    ("(yt-dlp_linux and yt-dlp.exe from the official release)",
     "(yt-dlp_linux from the official release)"),
    ("(deno-x86_64-unknown-linux-gnu and deno-x86_64-pc-windows-msvc\n          from the official release)",
     "(deno-x86_64-unknown-linux-gnu from the official release)"),
    ("To disable the bundled executables entirely",
     "This is the build for Modrinth and CurseForge: it carries no windows/amd64 executables,\n"
     "because CurseForge does not allow .exe files in a jar. On Windows, put ffmpeg (and yt-dlp\n"
     "and deno for platform links) on the PATH, or set \"ffmpegBinary\" and \"ytdlpBinary\" in\n"
     "config/jockey_radio.json.\n\n"
     "To disable the bundled executables entirely"),
]


def fail(msg):
    sys.exit(f"linux-only-jar: {msg}")


def rewrite_notice(text):
    for old, new in NOTICE_EDITS:
        if text.count(old) != 1:
            fail(f"the notice no longer reads as expected; update NOTICE_EDITS for: {old[:60]!r}")
        text = text.replace(old, new)
    kept = [line for line in text.splitlines(keepends=True) if not line.startswith("windows-amd64/")]
    return "".join(kept)


def main():
    if len(sys.argv) != 2:
        fail("usage: tools/linux-only-jar.py <release jar>")
    src = sys.argv[1]
    root, ext = os.path.splitext(src)
    dst = root + "-linux" + ext
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            if info.filename.startswith(WINDOWS_DIR):
                continue
            data = zin.read(info.filename)
            if info.filename == NOTICE:
                data = rewrite_notice(data.decode("utf-8")).encode("utf-8")
            zout.writestr(info, data, compress_type=info.compress_type)

    with zipfile.ZipFile(dst) as z:
        names = z.namelist()
        notice = z.read(NOTICE).decode("utf-8")
        exe = [n for n in names if n.lower().endswith(EXE_LIKE)]
        if exe:
            fail(f"{dst} still holds {exe}")
        if "windows-amd64/" in notice:
            fail("the notice still lists a windows-amd64 file")
        binaries = [n for n in names if n.startswith(LINUX_DIR) and not n.endswith("/")]
        for n in binaries:
            sha = hashlib.sha256(z.read(n)).hexdigest()
            if sha not in notice:
                fail(f"{n} has SHA-256 {sha}, which the notice does not list")
            print(f"  listed  {n}")
    print(f"wrote {dst} ({os.path.getsize(dst) / 1e6:.1f} MB): no exe-like entries, "
          f"{len(binaries)} linux binaries, each listed by SHA-256 in its notice")


if __name__ == "__main__":
    main()
