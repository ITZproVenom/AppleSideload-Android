# AppleSideload for Android

An Android app (Kotlin + Jetpack Compose) that sideloads IPAs onto an iPhone or iPad straight from the Android phone, using a free Apple ID. You don't need a PC, a Mac, a companion app or a web service you run yourself.

Download the APK from [Releases](https://github.com/ITZproVenom/AppleSideload-Android/releases); what changed in each build is in [CHANGELOG.md](CHANGELOG.md).

It does the same job as AltStore, SideStore and SideInstaller, but the whole pipeline runs on the Android device:

1. **USB / usbmux**: talks to the iPhone over the USB host port (Apple vendor 0x05AC, mux interface 255/254/2), with Wi-Fi discovery of `_apple-mobdev2._tcp` for devices that are already paired.
2. **Lockdown pairing**: generates a pairing record (root, host and device certificates), shows the Trust prompt on the iPhone, and stores the record encrypted with an Android Keystore key.
3. **Apple ID**: Grand Slam SRP-6a sign-in with trusted-device or SMS two-factor, then the Xcode app token.
4. **Developer services**: team, device registration, free development certificate (the CSR is generated on the phone), App IDs and the provisioning profile.
5. **Signing**: rewrites bundle identifiers, then signs every Mach-O (fat and thin) with SHA-1 + SHA-256 CodeDirectories, the CMS signature, the DER entitlements iOS 15+ requires, and `_CodeSignature/CodeResources`. Nested frameworks, plugins and watch apps are signed first.
6. **Install**: uploads to `/PublicStaging` over AFC and installs through `installation_proxy`, showing live progress.
7. **Remote Pairing (iOS 17 and later, the only wireless route on iOS 27)**: the phone offers itself as a pairable host over Bonjour (`_remotepairing-pairable-host._tcp`) with a PIN, or pairs over the cable through remotepairingd's lockdown service. Over Wi-Fi it then opens the CoreDevice tunnel (pair-verify, TLS-PSK, a userspace IPv6/TCP stack and RemoteServiceDiscovery) and reaches lockdown, AFC, house_arrest and installation_proxy through it.

SideStore and LiveContainer IPAs are supported, including their app groups and nested bundles.

## Honest limitations

- **Anisette attestation.** Apple sign-in needs `X-Apple-I-MD` headers that only Apple's closed ADI code can produce. Like SideStore and SideInstaller, the app gets them by default from the public SideStore anisette server (`https://ani.sidestore.io`), using the live server list with a bundled fallback. You can enter your own server in Settings. Your Apple ID password is **never** sent to the anisette server; it goes only to Apple through SRP. On-device ADI isn't compiled in, and the app says so instead of pretending it works.
- **Free Apple ID limits** (Apple's rules): apps expire after 7 days, at most 3 sideloaded apps at once, 10 App IDs per 7 days, and the free development certificates are limited. If Apple already holds a certificate whose private key is on another machine, the app explains this and offers to revoke it.
- **iOS 17+**: over the cable, install, AFC and pairing work through lockdown. Over Wi-Fi, an iPhone paired through Remote Pairing is reached through the CoreDevice tunnel; iOS 27 no longer answers lockdown over Wi-Fi at all, so there that is the only wireless route. Developer-disk and debug services (JIT) are not implemented.
- **SideStore on iOS 27.** SideStore refreshes there only with a Remote Pairing file, and in September 2026 only SideStore's own nightly build reads one; the stable releases and every LiveContainer build so far do not. On iOS 27 the app therefore installs the newest build, nightly included, checks it, and tells you when its SideStore cannot refresh on the iPhone yet (see [Using it](#using-it)).
- **Not fully tested on hardware.** The USB fixes come from logs of a real iPhone. Remote Pairing, the Wi-Fi tunnel and the heartbeat are tested only against simulated iPhones (and BouncyCastle's TLS), and Apple ID sign-in only against Apple's real certificate chain, not with a real account. Every failure names the operation, the iOS version, the technical reason, the limitation and whether a legitimate alternative exists. Copy the Activity log (Settings › Troubleshooting) when you report a problem.

No enterprise certificates and no manual `.p12` or `.mobileprovision` files. Developer Mode is iOS's own requirement for any development-signed app since iOS 16.

## Using it

The app has five tabs: **iPhone** (connect, pair, paired and nearby iPhones), **Install**, **Apps** (what is on the iPhone, with remove), **Account** (Apple ID, team, certificate) and **Settings** (web controller, attestation server, discovery, and the Activity log under Troubleshooting).

The Android app only does the installing. As with SideInstaller, refreshing happens on the iPhone: SideStore re-signs itself, LiveContainer and your apps every day over LocalDevVPN, so you don't need this phone after setup.

1. Connect the iPhone to the Android phone with a cable and allow USB access. You only need the cable to pair the first time. On iOS 17 and later that also sets up Remote Pairing, so afterwards the iPhone can be reached over the same Wi-Fi.
   - **No cable (iOS 27 included)**: on the **iPhone** tab tap *Pair wirelessly*, then on the iPhone open Settings › Privacy & Security › Developer Mode, pick this phone, and type the PIN the Android phone shows.
2. **iPhone**: tap *Connect*, then *Trust* on the iPhone with it unlocked (over the cable). An iPhone paired for Wi-Fi is listed under *Paired for Wi-Fi* with *Connect*; you can also connect by IP address.
3. **Account**: sign in with your Apple ID (two-factor by trusted device or SMS). If your Apple ID belongs to several teams, pick the one to sign with.
4. **Install**: the checklist shows anything still missing. Then pick:
   - **SideStore + LiveContainer**: downloads `LiveContainer+SideStore.ipa` from LiveContainer's GitHub releases (*SideStore only* downloads `SideStore.ipa` from SideStore's). Up to iOS 26 that is the latest stable release; on iOS 27 and later it is the newest build, nightly included, because only those can refresh there. The downloaded build is checked, and if its SideStore cannot refresh on iOS 27 yet the app says so and suggests *SideStore only*. Or:
   - **Your own IPA**: choose any `.ipa` on the phone, or open one from a file manager or browser and pick AppleSideload.
5. On the iPhone (the app lists these steps after the install): trust the developer app (Settings › General › VPN & Device Management), turn on Developer Mode (Settings › Privacy & Security), install and connect **LocalDevVPN**, open SideStore, sign in with the same Apple ID, and refresh every day.

### What the install does for SideStore (from SideInstaller and isideload)

- Bundle IDs become `<original>.<TEAMID>`; every extension gets its own App ID; all of them share one app group (`group.com.SideStore.SideStore.<TEAMID>` for the LiveContainer build).
- `ALTAppGroups`, `ALTCertificateID` and `ALTCertificate.p12` (protected with the certificate's machine id) are written into SideStore's bundle, so SideStore signs with the same certificate.
- LiveContainer gets its `com.kdt.livecontainer.shared` keychain groups.
- After installing, the pairing files are written into SideStore's container through house_arrest: `ALTPairingFile.mobiledevicepairing` for older builds, plus, for nightlies from September 2026, `PairingFile_Lockdown.plist` and (when this phone has a Remote Pairing record for the iPhone) `PairingFile_RemoteRP.plist`, with `isPairingReset = false`. `activePairingProtocol` is `lockdown`, except on iOS 27 or without a complete lockdown record, where it is `rppairing`: iOS 27's lockdownd resets connections that come through LocalDevVPN. Wireless lockdown (`EnableWifiDebugging`) is switched on as well.
- `Account.sideconf` is not written: it needs a provisioned anisette v3 state that the V1 anisette servers don't give. SideStore asks you to sign in on first launch instead.

The **Activity log** (Settings › Troubleshooting) shows the tagged logs (`[USB] [USBMUX] [LOCKDOWN] [PAIR] [APPLE] [SIGN] [INSTALL]`), newest first. Apple IDs, keys, tokens and device identifiers are replaced before anything is written to it; device names and network addresses stay.

## Web controller (LAN)

The app runs a small web server (port 8686 by default; the port can be changed in Settings while the controller is off). It is **on by default**: it starts with the app, and once you turn it off (Settings › Web controller, or *Stop* in its notification) it stays off until you turn it on again. Open the address shown in Settings or in the notification in any browser on the same network and you get every feature of the app from there: device list and connect (wired USB, wireless by IP, and wireless pairing with the PIN shown in the page), Trust/pairing status, Apple ID sign-in with two-factor codes and SMS, team choice, certificate revoke, installing SideStore + LiveContainer or SideStore, uploading and installing a custom IPA with upload progress, the installed-apps list with remove, live logs with download, and the attestation and discovery settings.

- It listens on every interface, so it is reachable over Wi-Fi, the phone's hotspot, and USB or Ethernet tethering. Settings lists each address.
- It runs as a foreground service with a notification (address + Stop) and holds a wake lock and Wi-Fi lock while on, so it keeps answering with the screen off. Turn it off when you don't need it, to save battery. On Android 13 and later the app asks once for permission to show the notification; without it the controller still runs and its address is in Settings.
- There is no login, by design: anyone on the same network who opens the page can use it, so turn it off on networks you don't trust. What it refuses:
  - devices that are not on the phone's network: only this phone, private and link-local addresses, and the phone's own Wi-Fi or Ethernet subnet are answered, and nothing that arrives over mobile data;
  - requests that name the phone by anything other than its IP address or `localhost`, which stops DNS rebinding (a website pointing its own name at the phone to read the controller through your browser);
  - requests that change something and come from another website (checked with the `Origin` header), so a page open in a browser on the network cannot drive the controller.
- The phone and the browser share one state. Only one operation runs at a time; asking for a second one while another is running is refused with the name of the running one.
- USB access is granted by Android itself: the first time a cable-connected iPhone is used, tap Allow in the dialog on the Android phone.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. CI (GitHub Actions) builds and tests every pull request, and on every push to `main` also publishes a signed APK as release `build-<number>`, with the *Unreleased* section of [CHANGELOG.md](CHANGELOG.md) as its notes. Pushes that only change Markdown files don't make a release.

## Modules

`core` (logging, plists, binary plists) · `device` (USB, usbmux, TLS, lockdown, AFC, installation_proxy, misagent, Remote Pairing and the CoreDevice tunnel) · `apple` (SRP, anisette, GSA auth, developer services) · `signing` (Mach-O, CodeDirectory, CMS, CodeResources, IPA) · `sideload` (the engine and the official SideStore/LiveContainer downloads) · `app` (Compose UI and the web controller).

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for credits.
