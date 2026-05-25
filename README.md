# SSHPal

A native Android companion that turns your phone into a thin client
for an SSH-reachable developer workstation. Browse files over SFTP,
edit them inline, run git, and talk to a remote Claude Code session
— without ever opening a terminal.

This is the v1 skeleton (G1 in the plan). It builds an APK that opens
to an empty Material 3 surface. No real features yet.

## Build

Requires JDK 17 and the Android SDK (compile-sdk 34).

```bash
./gradlew assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device:

```bash
./gradlew installDebug
```

## Where the plan lives

See `../plan-ssh-pal-v1.md` for the goal-oriented roadmap (G1–G7) and
`../CLAUDE.md` for the workdir conventions.

## Package

`cz.netbite.sshpal` (locked at scaffold time).
