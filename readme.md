<h1 align="center">Moons</h1>

<p align="center"><a href="docs/README.zh-CN.md">简体中文</a> · English</p>

Moons is a Windows client for Minecraft Java Edition with configurable modules, local presets and YSM models.

Supports **26.1.2**, **26.2**, **26.3-rc-2** and **26.3-rc-3**.

## Version Support

Moons actively supports the four most recent stable Minecraft release lines.

Development builds such as snapshots, pre-releases and release candidates are only supported for the newest upcoming Minecraft version.

Older preview builds and retired Minecraft versions may remain available through historical releases or CI artifacts, but receive no further fixes or compatibility updates.

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

## Inspired Projects

- [CCBlueX/LiquidBounce](https://github.com/CCBlueX/LiquidBounce)
- [sdf123098/Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher)
- [IamNespola/OpenMyau-Plus](https://github.com/IamNespola/OpenMyau-Plus)

## License

[GPL-3.0](LICENSE). Third-party code retains its original license and notices.

Read the full [security agreement, disclaimer and usage notice](docs/DISCLAIMER.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
