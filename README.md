# YGuard Plugin

YGuard is a Paper plugin that verifies encrypted client attestations, applies configured enforcement actions, and persists account and hardware identifier bans.

## Components

This repository contains the server plugin and includes the client project as a submodule. The client project includes the native library as a nested submodule.

## Requirements

- Java 21
- Paper 1.21.11

## Build

```powershell
git clone --recurse-submodules https://github.com/J3y0r/YGuard-Plugin
Set-Location YGuard-Plugin
.\gradlew.bat test shadowJar
```

The plugin JAR is written to `build\libs`.

See [build-and-deployment.md](build-and-deployment.md) for release and deployment instructions.

## License

This project is licensed under the GNU General Public License v3.0 only. See [LICENSE](LICENSE).
