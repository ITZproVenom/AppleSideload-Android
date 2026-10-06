# Third-party licenses and credits

The protocol implementations in this project are original Kotlin code written from public documentation and from studying these open-source projects. No source code from them is copied into this repository except where stated.

| Project | License | Used for |
| --- | --- | --- |
| [libimobiledevice](https://github.com/libimobiledevice/libimobiledevice) | LGPL-2.1 | Reference for lockdown, pairing record layout, AFC, installation_proxy and misagent |
| [libusbmuxd](https://github.com/libimobiledevice/libusbmuxd) / usbmuxd | LGPL-2.1 / GPL-2.0 | Reference for the usbmux wire protocol and TCP-over-mux |
| [go-ios](https://github.com/danielpaulus/go-ios) | MIT | Reference for usbmux, lockdown and iOS 17 tunnel behaviour |
| [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) | GPL-3.0 | Reference for lockdown services and iOS 17+ RemoteXPC |
| [Jitterbug](https://github.com/osy/Jitterbug) | Apache-2.0 | Reference for on-device pairing and lockdown from a mobile host |
| [SideStore](https://github.com/SideStore/SideStore) | AGPL-3.0 | Reference for the anisette server protocol; the public anisette server list is used at runtime |
| [SideInstaller](https://github.com/FrizzleM/SideInstaller) | see repository | Reference for using the SideStore anisette servers |
| [AltStore](https://github.com/altstoreio/AltStore) / AltSign | AGPL-3.0 / see repository | Reference for Apple developer services and the bundle signing flow |
| [Bouncy Castle](https://www.bouncycastle.org/) (bcprov, bcpkix 1.78.1) | MIT (Bouncy Castle License) | X.509 certificates, CSR and CMS signing (linked library) |
| AndroidX, Jetpack Compose, Kotlin | Apache-2.0 | Application framework (linked libraries) |

Apple, iPhone, iOS and Xcode are trademarks of Apple Inc. This project is not affiliated with or endorsed by Apple.
