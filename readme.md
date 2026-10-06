> [!IMPORTANT]
> **Minecraft 1.8.9 users must build this branch themselves.** Check out `legacy/1.8.9` and run `.\gradlew.bat moonsPackages` with JDK 25 on Windows x64. Use the resulting `build/dist/Moons-install.exe` and `build/dist/Moons.exe` together. See [the build instructions](docs/BUILDING.md). Main-branch release packages target modern Minecraft versions.

<h1 align="center">Moons</h1>

<p align="center"><a href="docs/README.zh-CN.md">简体中文</a> · English</p>

Moons is a Windows client for Minecraft Java Edition with configurable modules, local presets and YSM models. [Website](https://moons.cendreal.com/en/).

The `legacy/1.8.9` branch adapts the complete client framework to vanilla Minecraft 1.8.9: 64 applicable modules, the full settings UI, presets and YSM. SilentAura retains Legacy combat and its 47 applicable settings. AutoMace, AutoSpear and AutoTotem have no equivalent game mechanics and are omitted.

This branch is a development snapshot awaiting user testing. It has not been merged into `main` or published as a stable release; existing checks cover only part of the functionality.

> [!WARNING]
> **Published for learning, research, and technical exchange only.**
>
> **This project will not generate profit through any means.** Maintainers commit to no direct or indirect profit-making through the project. Official source code, builds, and features are provided free of charge.
>
> **Game modifications target Minecraft: Java Edition itself as released by Mojang and must comply with the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and applicable official terms. Their scope does not extend to independent third-party software, services, or resources.** Dependencies and external content retain their own licenses; this is not official approval or a comprehensive compliance guarantee.
>
> **The authors cannot individually review, monitor, or control others' actual conduct.** Each actor remains legally responsible for their own conduct.
>
> **Provided as is; users bear the risks of use to the extent permitted by law.** Read the [full disclaimer](docs/DISCLAIMER.md) before use. Non-excludable liability and statutory rights remain unaffected.

Supports **vanilla Minecraft 1.8.9 on Java 25 x64**. Use the main branch for modern Minecraft versions. Core-modified clients are not covered by this branch's verification.

## Risk and Responsibility Notice

- **Verify permissions.** Users must comply with applicable laws, licenses, and service rules. Educational purpose does not replace required authorization.
- **Assess risks.** The project changes game behavior and may affect operation, data, and related services. Back up important data and use an authorized environment.
- **Responsibility follows conduct.** Publishing source does not itself establish participation in or a guarantee of third-party conduct. Unless required by law or separately and validly agreed, the authors assume no guarantee, payment, or indemnity obligation for independent third-party conduct.
- **Provided as is.** To the fullest extent permitted by law, no warranty or liability for related damages or compensation is assumed, and no particular usage or maintenance outcome is promised.
- **Respect third-party rights.** External content and third-party components retain their own licenses; the project's open-source license does not automatically cover them.
- **Legal boundaries remain.** This notice does not restrict GPL rights or exclude non-excludable liability. See the [full notice](docs/DISCLAIMER.md) and [LICENSE](LICENSE).

## How to Use

1. Build `legacy/1.8.9` yourself using [BUILDING.md](docs/BUILDING.md), then use `build/dist/Moons-install.exe` and `build/dist/Moons.exe` from that same build.
2. Run the installer to install or update dependencies.
3. Start vanilla Minecraft 1.8.9 with Java 25 x64, then run the loader to select the game and load Moons.
4. Press **Right Shift** to open ClickGUI (default binding).

If the dependency version is unchanged and the installed files are intact, only the loader needs updating. Otherwise, run the matching installer. The self-built installer includes the complete UI runtime and YSM dependencies and works offline.

## How to Save a Config

1. Open **ClickGUI → Configs**.
2. Changes save to the currently loaded JSON config. Choose **Create** to copy all current settings into a new config.
3. Select another config and choose **Load** to switch. **Overwrite** replaces the selected target without switching the current config.

Startup loads the ordinary `config/profiles/default.json`. All legacy `moons.properties` values migrate into it on the first run; existing named JSON configs remain available.

## How to Use YSM Models

1. Put your model in the folder shown on the YSM page. The default is `%APPDATA%\.moons\data\ysm\models`.
2. Open **ClickGUI → YSM**, choose **Refresh**, select the model, then **Apply**.
3. Choose **Use vanilla** to restore the original player model.

## How to Build

Prepare Windows x64, JDK 25 and the native build tools listed in [BUILDING.md](docs/BUILDING.md). Open PowerShell in the project folder:

```powershell
.\gradlew.bat moonsPackages
```

The outputs are `build/dist/Moons-install.exe`, `build/dist/Moons.exe` and `build/dist/dependencies/moons-ui-runtime.jar`. In an IDE, import the Gradle project with JDK 25 and run the same task.

## How to Contribute

- Open an [issue](https://github.com/LoscX-X/Moons/issues) with the Minecraft version, reproduction steps and relevant logs.
- For code changes, compile and check the affected functionality, then submit a PR. See [build and verification](docs/BUILDING.md).

## Project Activity

![Moons repository activity](https://repobeats.axiom.co/api/embed/fbaacbea8a2feb78305a937b23e2981cf6bf564a.svg "Repobeats analytics image")

## Inspired Projects

- [CCBlueX/LiquidBounce](https://github.com/CCBlueX/LiquidBounce)
- [sdf123098/Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher)
- [IamNespola/OpenMyau-Plus](https://github.com/IamNespola/OpenMyau-Plus)

## License

[GPL-3.0](LICENSE). Third-party code retains its original license and notices.

Read the full [security agreement, disclaimer and usage notice](docs/DISCLAIMER.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
