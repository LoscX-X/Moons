<h1 align="center">Moons</h1>

<p align="center"><a href="README.zh-CN.md">简体中文</a> · English</p>

Moons is a Windows client for Minecraft Java Edition with configurable modules, local presets and YSM models.

Supports **26.1.2**, **26.2**, **26.3-rc-2** and **26.3-rc-3**.

## Version Support

Moons actively supports the four most recent stable Minecraft release lines.

Development builds such as snapshots, pre-releases and release candidates are only supported for the newest upcoming Minecraft version.

Older preview builds and retired Minecraft versions may remain available through historical releases or CI artifacts, but receive no further fixes or compatibility updates.

## How to Use

1. Download `moons-full.exe` from [Releases](https://github.com/LoscX-X/Moons/releases).
2. Start a supported Minecraft version.
3. Run the launcher, select the game process, and load Moons.
4. Press **Right Shift** to open ClickGUI (default binding).

The smaller `moons.exe` downloads and caches the matching UI runtime on first use. Development builds are available from [Actions](https://github.com/LoscX-X/Moons/actions).

## How to Save a Config

1. Open **ClickGUI → Configs**.
2. Enter a name, choose **Create**, then **Save** to store your current settings.
3. Select a saved config and choose **Load** to apply it.

## How to Use YSM Models

1. Put your model in the folder shown on the YSM page. The default is `%APPDATA%\.moons\data\ysm\models`.
2. Open **ClickGUI → YSM**, choose **Refresh**, select the model, then **Apply**.
3. Choose **Use vanilla** to restore the original player model.

## How to Build

Prepare Windows x64, JDK 25 and the native build tools listed in [BUILDING.md](BUILDING.md). Open PowerShell in the project folder:

```powershell
.\gradlew.bat moonsFullExe
```

The result is `build/dist/moons-full.exe`. In an IDE, import the Gradle project with JDK 25 and run the same task.

## How to Contribute

- Open an [issue](https://github.com/LoscX-X/Moons/issues) with the Minecraft version, reproduction steps and relevant logs.
- For code changes, compile and check the affected functionality, then submit a PR. See [build and verification](BUILDING.md); network changes should follow [these conventions](NETWORK_DEVELOPMENT.md).

## License

[GPL-3.0](LICENSE). Third-party code retains its original license and notices.

Read the full [security agreement, disclaimer and usage notice](DISCLAIMER.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
