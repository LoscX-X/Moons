<h1 align="center">Moons</h1>

<p align="center"><a href="docs/README.zh-CN.md">简体中文</a> · English</p>

Moons is a Windows client for Minecraft Java Edition with configurable modules, local presets and YSM models.

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

Supports **26.1.2**, **26.2**, **26.3**, and **26.4-snapshot-1**.

## Version Support

Moons maintains the stable releases listed above and the listed 26.4 preview build.

Development builds such as snapshots, pre-releases and release candidates are only supported for the newest upcoming Minecraft version.

Older preview builds and retired Minecraft versions may remain available through historical releases or CI artifacts, but receive no further fixes or compatibility updates.

## Risk and Responsibility Notice

- **Verify permissions.** Users must comply with applicable laws, licenses, and service rules. Educational purpose does not replace required authorization.
- **Assess risks.** The project changes game behavior and may affect operation, data, and related services. Back up important data and use an authorized environment.
- **Responsibility follows conduct.** Publishing source does not itself establish participation in or a guarantee of third-party conduct. Unless required by law or separately and validly agreed, the authors assume no guarantee, payment, or indemnity obligation for independent third-party conduct.
- **Provided as is.** To the fullest extent permitted by law, no warranty or liability for related damages or compensation is assumed, and no particular usage or maintenance outcome is promised.
- **Respect third-party rights.** External content and third-party components retain their own licenses; the project's open-source license does not automatically cover them.
- **Legal boundaries remain.** This notice does not restrict GPL rights or exclude non-excludable liability. See the [full notice](docs/DISCLAIMER.md) and [LICENSE](LICENSE).

## How to Use

1. Download `moon-install-<version>-<commit>.exe` and `moon-<version>-<commit>.exe` from the same [release](https://github.com/LoscX-X/Moons/releases).
2. Run the installer to install or update dependencies.
3. Start a supported Minecraft version, then run the loader to select the game and load Moons.
4. Press **Right Shift** to open ClickGUI (default binding).

If the dependency version is unchanged and the installed files are intact, only the loader needs updating. Otherwise, run the matching installer. The installer includes the complete UI runtime and YSM dependencies and works offline. Development builds are available from [Actions](https://github.com/LoscX-X/Moons/actions).

## How to Save a Config

1. Open **ClickGUI → Configs**.
2. Enter a name, choose **Create**, then **Save** to store your current settings.
3. Select a saved config and choose **Load** to apply it.

## How to Use YSM Models

1. Put your model in the folder shown on the YSM page. The default is `%APPDATA%\.moons\data\ysm\models`.
2. Open **ClickGUI → YSM**, choose **Refresh**, select the model, then **Apply**.
3. Choose **Use vanilla** to restore the original player model.

## How to Build

Prepare Windows x64, JDK 25 and the native build tools listed in [BUILDING.md](docs/BUILDING.md). Open PowerShell in the project folder:

```powershell
.\gradlew.bat moonsPackages
```

The outputs are `build/dist/moon-install.exe`, `build/dist/moon.exe` and `build/dist/dependencies/moons-ui-runtime.jar`. In an IDE, import the Gradle project with JDK 25 and run the same task.

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
