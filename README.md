# AppleSideload for Android

An Android app (Kotlin + Jetpack Compose) that sideloads IPAs onto an iPhone or iPad straight from the Android phone, using a free Apple ID. You don't need a PC, a Mac, a companion app or a web service you run yourself.

It does the same job as AltStore, SideStore and SideInstaller, but the whole pipeline runs on the Android device:

1. **USB / usbmux**: talks to the iPhone over the USB host port (Apple vendor 0x05AC, mux interface 255/254/2), with Wi-Fi discovery of `_apple-mobdev2._tcp` for devices that are already paired.
2. **Lockdown pairing**: generates a pairing record (root, host and device certificates), shows the Trust prompt on the iPhone, and stores the record encrypted with an Android Keystore key.
3. **Apple ID**: Grand Slam SRP-6a sign-in with trusted-device or SMS two-factor, then the Xcode app token.
4. **Developer services**: team, device registration, free development certificate (the CSR is generated on the phone), App IDs and the provisioning profile.
5. **Signing**: rewrites bundle identifiers, then signs every Mach-O (fat and thin) with SHA-1 + SHA-256 CodeDirectories, the CMS signature, the DER entitlements iOS 15+ requires, and `_CodeSignature/CodeResources`. Nested frameworks, plugins and watch apps are signed first.
6. **Install**: uploads to `/PublicStaging` over AFC and installs through `installation_proxy`, showing live progress.

SideStore and LiveContainer IPAs are supported, including their app groups and nested bundles.

## Honest limitations

- **Anisette attestation.** Apple sign-in needs `X-Apple-I-MD` headers that only Apple's closed ADI code can produce. Like SideStore and SideInstaller, the app gets them by default from the public SideStore anisette server (`https://ani.sidestore.io`), using the live server list with a bundled fallback. You can enter your own server in Settings. Your Apple ID password is **never** sent to the anisette server; it goes only to Apple through SRP. On-device ADI isn't compiled in, and the app says so instead of pretending it works.
- **Free Apple ID limits** (Apple's rules): apps expire after 7 days, at most 3 sideloaded apps at once, 10 App IDs per 7 days, and the free development certificates are limited. If Apple already holds a certificate whose private key is on another machine, the app explains this and offers to revoke it.
- **iOS 17+**: install, AFC and pairing all work through lockdown over USB. Developer-disk and debug services that need the RemoteXPC/RSD tunnel are not implemented, and the Device screen says so.
- **Untested on hardware so far.** Every failure surfaces the operation, the iOS version, the technical reason, the limitation and whether a legitimate alternative exists. Copy the Diagnostics log when you report a problem.

No enterprise certificates, no manual `.p12` or `.mobileprovision` files, and no Developer Mode requirement.

## Using it

1. Connect the iPhone to the Android phone with a USB-C (or OTG + Lightning) cable and allow USB access.
2. **Device**: connect, then pair and tap *Trust* on the iPhone.
3. **Account**: sign in with your Apple ID and pick a team.
4. **Install**: choose an `.ipa` and install.
5. **Apps**: view and remove sideloaded apps. **Diagnostics**: tagged logs (`[USB] [USBMUX] [LOCKDOWN] [PAIR] [APPLE] [SIGN] [INSTALL]`), redacted when exported.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. CI (GitHub Actions) builds, tests and publishes a signed APK on every push to `main`.

## Modules

`core` (logging, plists, binary plists) · `device` (USB, usbmux, TLS, lockdown, AFC, installation_proxy, misagent) · `apple` (SRP, anisette, GSA auth, developer services) · `signing` (Mach-O, CodeDirectory, CMS, CodeResources, IPA) · `sideload` (the engine) · `app` (Compose UI).

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for credits.
