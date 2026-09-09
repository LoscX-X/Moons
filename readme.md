# Moons

[简体中文](README.zh-CN.md) | **English**

A Windows client project for Minecraft Java Edition, supporting **26.1.2 / 26.2 / 26.3-pre-3** and loaded through a JNI/JVMTI bridge. Client logic is shared across versions, with version differences handled by dedicated adapters.

26.3 support currently targets `26.3-pre-3`. Its source directory remains `26_3` and payload key remains `26.3` when the target is updated to the final release.

## Download and Run

Builds are available from [GitHub Releases](https://github.com/LoscX-X/Moons/releases) and the artifacts of the corresponding Actions run.

- **Full package**, `moons-full.exe`: includes the UI runtime.
- **Lightweight package**, `moons.exe`: downloads and caches the matching UI runtime from the same release on first use.

Start a supported Minecraft version before running the launcher.

## Local Build

### Requirements

- Windows x64 and an x64 JDK 25.
- Visual Studio 2022 or Build Tools 2022 with the **Desktop development with C++** workload, including the Windows SDK and CMake 3.20+.
- The .NET Framework 4.x C# compiler.

The repository includes the Gradle Wrapper; a separate Gradle installation is not required.

### Build Commands

Open PowerShell at the repository root and set the JDK path for your installation:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\gradlew.bat moonsPackages
```

Output is written to `build/dist/`. Individual packages can also be built separately:

| Task | Output |
|---|---|
| `moonsPackages` | All packages, covering all supported Minecraft versions |
| `moonsFullExe` | `moons-full.exe` |
| `moonsExe` | `moons.exe` |
| `moonsUiRuntime` | `dependencies/moons-ui-runtime.jar` and its SHA-256 checksum file |

The first build requires an internet connection to download dependencies. When building the lightweight package locally, set `-Pmoons_ui_download_url=<URL>` to the download URL of the matching UI JAR.

### Build the Java Payload Separately

```powershell
.\gradlew.bat moonsJar '-Pminecraft_version=26.1.2'
.\gradlew.bat moonsJar '-Pminecraft_version=26.2'
.\gradlew.bat moonsJar '-Pminecraft_version=26.3-pre-3'
```

The outputs are `build/dist/agent/26_1/moons.jar`, `build/dist/agent/26_2/moons.jar`, and `build/dist/agent/26_3/moons.jar`. These payloads are loaded by the bridge and cannot be launched with `java -jar`.

## GitHub Actions

Open **Actions → Project checks and packages → Run workflow** and select:

- `all`: all packages; the default option.
- `full` / `download`: the full / lightweight package, together with the UI runtime.
- `ui-runtime`: the UI runtime only.

Pushes to the main branch build packages and create a prerelease after checks pass. Pull requests run checks only. Selecting `ui-runtime` manually skips Minecraft checks. The workflow configures the lightweight launcher's dependency download URL automatically.

## Development and License

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines in Chinese and [native-agent/README.md](native-agent/README.md) for native startup-agent tests.

See [LICENSE](LICENSE) for the license.

---

## Security Agreement, Disclaimer, and Usage Notice

### 1. Nature of the Project

This is an open-source research and testing project that runs within Minecraft Java Edition. Its primary purpose is to study, research, and validate runtime injection, event interception, module loading, and related software engineering techniques.

This project is not an official Minecraft product. It is not endorsed, sponsored, or authorized by Mojang Studios or Microsoft, and does not represent their views.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

### 2. Scope of Use

This project is intended solely for lawful software research, personal learning, compatibility testing, and security testing with explicit authorization.

Users must not use this project in ways that violate applicable laws, platform rules, software license agreements, or the lawful rights and interests of others, including but not limited to:

- Accessing, modifying, interfering with, or damaging third-party devices, accounts, servers, or data without authorization.
- Stealing, collecting, uploading, selling, or disclosing personal information, authentication credentials, or other sensitive data.
- Circumventing access controls, security verification, payment mechanisms, or platform restrictions.
- Conducting fraud, attacks, extortion, malicious control, persistent unauthorized access, or other unlawful activities.
- Stealing accounts or tokens, remotely controlling systems, exfiltrating data, maintaining concealed persistence, or bypassing security software.
- Presenting this project as an official product of Minecraft, Mojang Studios, or Microsoft.

### 3. Data and Privacy

This project is not designed to collect, steal, monitor, upload, or disclose to external parties any personal information, account credentials, chat records, device information, or other nonpublic data belonging to users or third parties.

Unless otherwise stated in the project documentation, official releases should not initiate connections to external services unrelated to the project's functionality.

Users should independently review the source code, build artifacts, configuration files, and network behavior they use. The project maintainers cannot guarantee the security, integrity, or data-handling behavior of third-party modifications, unofficial builds, redistributed versions, external plugins, or user-added code.

Obtain source code and build artifacts only from the official repository or release channels explicitly listed by this project.

### 4. User Responsibilities

By downloading, compiling, installing, running, modifying, or distributing this project, users acknowledge and agree that:

- They are responsible for obtaining any necessary authorization and complying with applicable local laws, Minecraft agreements, server rules, and other applicable terms.
- They must independently assess the security, stability, and compatibility risks associated with runtime injection and changes to game behavior.
- They should back up important files and prioritize use in isolated test environments, personal worlds, or environments where explicit authorization has been obtained.
- They bear responsibility for consequences arising from their violations of laws, agreements, server rules, or this notice.
- The project's name, open-source status, or research purpose does not guarantee that any particular use is lawful, compliant, or safe.

### 5. Risks

Runtime injection, bytecode modification, hooks, mixins, and similar mechanisms may conflict with other mods, loaders, game versions, security software, or system environments. Possible consequences include:

- Game crashes, corrupted worlds, or lost configuration.
- Reduced performance, malfunction, or version incompatibility.
- Rejected server connections, account restrictions, or other platform actions.
- Security software warnings, blocking, or false positives.
- Other losses caused by incorrect configuration, third-party modifications, or improper use.

Users should decide whether to run this project only after understanding these risks.

### 6. No Warranty

To the fullest extent permitted by applicable law, this project is provided "as is" and "as available", without any express or implied warranties, including warranties of merchantability, fitness for a particular purpose, accuracy, reliability, compatibility, security, or noninfringement.

The project authors and maintainers do not guarantee that the project:

- Will always be available, error-free, or uninterrupted.
- Will support every version of Minecraft, Java, mod loaders, or operating systems.
- Will avoid triggering security software, platform risk controls, or server detection.
- Will meet any particular purpose or produce any particular result.

To the fullest extent permitted by applicable law, the project authors, maintainers, and contributors are not liable for direct, indirect, incidental, special, punitive, or consequential losses arising from use of, or inability to use, this project.

Where applicable law does not permit the exclusion or limitation of certain liabilities, liability is limited to the minimum extent permitted by that law.

### 7. Intellectual Property

Minecraft, Mojang, Mojang Studios, Microsoft, and their associated names, trademarks, graphics, and game assets belong to their respective rights holders.

This project licenses only original code and content created by its authors and contributors under the open-source license included in the repository. This grant does not cover Minecraft itself, modified game clients or servers, official assets, or any third-party content that this project has no authority to relicense.

When copying, modifying, or distributing this project, users must also comply with:

- The project's open-source license.
- The Minecraft End User License Agreement and Usage Guidelines.
- The licenses of dependencies and third-party components used.
- Applicable local laws and regulations.

This notice does not replace or modify the rights and obligations established by the repository's open-source license. In the event of a conflict, the applicable license and mandatory legal provisions prevail.

### 8. Third-Party Content and Unofficial Versions

The project maintainers provide no warranties for third-party websites, mirrors, bundled distributions, derivative projects, modified versions, or unofficial builds, and accept no responsibility for their security, lawfulness, integrity, or availability.

Third-party modification, redistribution, or combined use of this project does not in itself imply approval, authorization, or cooperation by the project authors or maintainers.

[Project source code](https://github.com/LoscX-X/Moons)
