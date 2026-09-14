# YSM image decoders

`moons-ysm-images.jar` is independent of Minecraft and mod loaders. It provides
explicit WebP/AVIF decoding through `com.blanoir.moons.ysm.images.YsmImages`.
No global ImageIO providers or JNI libraries are installed into the game JVM.
Each adapter loads the jar through its own closeable module classloader.

## WebP

The Java VP8/VP8L decoder is taken from ImageStream, the dependency used by
Sparkle-Morpher. Encoder, JPEG, AVIF, ImageIO registration and CLI sources are
excluded. The retained `rip.ysm.imagestream` sources keep their upstream contents.
`WebpImages` supplies RIFF ALPH handling for lossy transparency: compressed and raw
planes, including horizontal, vertical and gradient predictors.

Source: https://github.com/TartaricAlkaline/ImageStream/tree/b543665ea567479fba1570cb20258ba87d4c36c4

The pinned ImageStream tree contains no top-level license file. This provenance
notice does not assign a new license to those upstream sources.

## AVIF

The upstream Java AVIF decoder did not preserve the colors/alpha of independent
fixtures. `AvifImages` therefore uses the official Windows x64 `avifdec.exe` from
libavif 1.4.2, bundled as `/avif/windows-x64/avifdec.exe`. It runs as an isolated
child process with two workers, dimension/size limits and a 20-second timeout.
Files are confined to a unique temporary directory and removed on completion.
A bounded weak-key PNG cache avoids repeated tool launches for the same texture.
Other operating systems need a corresponding AVIF implementation/tool bundle.

Release: https://github.com/AOMediaCodec/libavif/releases/tag/v1.4.2
Archive: windows-artifacts.zip
Archive SHA-256: cb2d9fea43dcbab1d0707e3b37eb7b08070ad2fb60a2c188c39ec12382c0484a
Executable SHA-256: a03664ddffb9d847eb484af6ceeffce96e03d641da5c577d26662487dee689f2
The extracted executable is verified before reuse/publishing in the temp cache.

The executable identifies libavif 1.4.2, dav1d 1.5.3, aom 3.14.1 and libyuv 1924;
PNG output also uses libpng and zlib. Their license and patent notices are included
under `META-INF/licenses`. This software is based in part on the work of the
Independent JPEG Group, as acknowledged by the bundled libavif license.

The image fixtures are synthetic and contain no model-author assets. Verification
checks dimensions, colors and transparency for WebP lossless/lossy and AVIF, plus
all four uncompressed WebP alpha predictors.
