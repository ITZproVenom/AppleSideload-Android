# Changelog

Every push to `main` is built by CI and published on the
[Releases](https://github.com/ITZproVenom/AppleSideload-Android/releases) page
as `build-<number>`. The build number is also the app's version code, so each
build installs over the previous one. The *Unreleased* section becomes the
notes of the next release.

## Unreleased: AFC staging fix

### Fixes
- Staging an IPA no longer fails with *an AFC request (operation 13) failed:
  the destination is not empty* when a previous install left a directory under
  `/PublicStaging`. The target path is cleared with `removeTree` before the
  upload, and the same cleanup runs after a successful install.

### Apple sign-in
- Signing in to an Apple ID always failed. Apple's sign-in server answers the
  identity the app presented (an old Xcode client) with *503 Service
  Unavailable*. The app now signs in the way SideStore and SideInstaller do,
  as macOS's own account service, with the requests Apple accepts today.
- The password proof (SRP) had three calculation errors, so Apple would have
  refused even the right password. It now matches the reference
  implementation, checked against values computed with it.
- The app sets up its own device identity with the anisette server
  (anisette v3) and keeps it on the phone. Apple sees the same device each
  time, so two-factor is needed once and your account lists one device. Like
  SideStore and SideInstaller, that device shows as a Mac: Apple has no
  sign-in for Android. Anisette servers that only offer the older API still
  work.
- Two-factor: the trusted phone numbers load, so codes by text message work
  (the list was always empty). A wrong code can be typed again, and if Apple
  asks for another code after the first one, the app asks for it instead of
  stopping.
- The token for Apple's developer service is requested and decrypted the way
  Apple sends it now.
- When Apple is busy (*429 Too Many Requests*), the app waits and tries twice
  more. Apple's error pages show as a short sentence with the status instead
  of an XML parser error.

### Fixes
- Error names in the Activity log stay readable in release builds. The code
  shrinker had shortened the app's own error names to single letters.
- On iOS 27 the iPhone can close its heartbeat service as soon as it opens.
  The log now says so as information instead of a warning; the connection
  was never affected.

## build-25: new look, web controller on by default, iOS 27 SideStore builds

### iOS 27: the right SideStore build
- When the iPhone runs iOS 27 or later, *SideStore + LiveContainer* and
  *SideStore only* install the newest build on GitHub, nightly builds
  included, instead of the latest stable release. On iOS 27 SideStore can
  only refresh with a Remote Pairing file, and in September 2026 only
  SideStore's own nightly reads one: the stable releases (SideStore 0.7.0-alpha,
  LiveContainer 3.8.0) and LiveContainer's nightly do not.
- Each downloaded build is checked for that support. When it is missing, the
  Install screen (and the web page) says so after the install, and suggests
  *SideStore only* or installing again from this app before the 7 days run
  out.
- Earlier iOS versions keep getting the latest stable release, as before.

### New look
- Five tabs: **iPhone**, **Install**, **Apps**, **Account** and **Settings**,
  plus an **Activity log** under Settings › Troubleshooting.
- Colour-coded status cards for the connection (ready, waiting for Trust,
  pairing, error) with the next step spelled out, and the wireless-pairing PIN
  in large digit boxes.
- Install shows a checklist (iPhone connected, signed in) with buttons to the
  right tab, live progress, and the steps to finish on the iPhone afterwards.
- Apps lists sideloaded apps apart from App Store apps (collapsed), and asks
  before removing anything. Forget and Revoke certificate ask first too.
- Account: show/hide password, a team picker when your Apple ID has several
  teams, and *Cancel sign-in* during two-factor.
- Light and dark themes, Material You colours on Android 12 and later, an
  adaptive launcher icon, and a real status-bar icon for the web controller's
  notification (it was a white square).
- Opening an `.ipa` from a file manager or browser goes straight to Install;
  plugging in an iPhone opens the iPhone tab.

### Web controller
- On by default. It starts with the app and stays off once you turn it off
  (Settings, or *Stop* in its notification). On Android 13 and later the app
  asks once for permission to show that notification.
- It answers only devices on the phone's own network: private and link-local
  addresses, or the phone's own Wi-Fi/Ethernet subnet. Nothing that arrives
  over mobile data is answered.
- It refuses requests that change something and come from another website
  open in a browser on the network, and requests that name the phone by
  anything but its IP address (DNS rebinding).

### Fixes
- Cancelling while the iPhone waits for Trust is no longer reported as a
  failure.
- The Account screen's note about the password is now accurate: it is never
  saved.
- The app no longer offers to open every file type, only IPA, ZIP and generic
  binary files.
- No black flash at startup.
- Instructions name the right tabs and settings.
- The attestation server field says that empty means the default server, and
  flags an address that does not start with `http://` or `https://`.
- Apps for which iOS reports no signer are listed apart instead of being
  called sideloaded.

## build-18: wireless on iOS 17 and later, including iOS 27
- Remote Pairing: pair wirelessly with a PIN (on the iPhone: Settings ›
  Privacy & Security › Developer Mode), or connect once by USB, which sets it
  up automatically.
- Over Wi-Fi the app opens the CoreDevice tunnel and reaches lockdown, AFC,
  house_arrest and installation_proxy through it. On iOS 27 this is the only
  wireless route.
- Paired iPhones are listed with Connect and Forget, on the phone and in the
  web controller.
- SideStore also gets `PairingFile_RemoteRP.plist`, with `rppairing` as the
  active protocol on iOS 27.
- The app answers the iPhone's heartbeat, so Wi-Fi sessions stay open.
- The version code follows the CI build number, so each build installs as an
  update.

## build-12: USB and Apple sign-in fixes
- Rewritten USB connection: usbmuxd framing and header sizes, flow control,
  queued reads, an idle timeout and zero-length packets. The USB channel is
  released when a start fails.
- Apple sign-in: Apple's published root certificates are trusted for apple.com
  hosts only, which fixes the certificate error at sign-in.
- No app-list errors when no iPhone is connected, and clearer errors when
  connecting over Wi-Fi fails.

## build-10: web controller
- Every feature of the app from a browser on the same network: Wi-Fi, the
  phone's hotspot or USB tethering.
- The phone and the browser share one state and run one operation at a time.
- USB plug and unplug are followed, and a failed connect or pairing releases
  the USB channel.

## build-4: SideStore + LiveContainer
- One-tap install of SideStore + LiveContainer, or SideStore only, from the
  official releases, done the way SideInstaller does it: TEAMID bundle IDs,
  per-extension App IDs, a shared app group, `ALTCertificate.p12`,
  LiveContainer's keychain groups, and the pairing file written into
  SideStore.
- Install your own IPA.
- Wireless mode: connect to a paired iPhone by IP address over Wi-Fi.

## build-1: first build
- usbmux over Android's USB host port, lockdown pairing with the Trust prompt,
  Apple ID sign-in with two-factor, the free development certificate and
  profiles, code signing on the phone, and install with live progress.
