# Android_Ghosts — DNSTT + OpenVPN

A phone-first Android VPN client branded **Android_Ghosts**, intended for ARM64 Android devices such as the Samsung Galaxy A56.

## Available connection engines

The app has two Android VPN engines, selected from the toolbar/side menu:

- **DNSTT** — bundled `ghostcore.aar`; supports DNS/UDP, DoH and DoT resolver transports, KCP, Noise and smux.
- **OpenVPN** — imports standard client `.ovpn` profiles and supports text certificate/key sidecar files that can be inlined during import.

Within DNSTT profiles, the engine also supports SSH mode, local SOCKS5 TCP CONNECT, HTTP proxy and local TCP port forwarding. These are modes/proxies carried by the DNSTT engine, not independent VPN engines. DNS transport selection is separate from the local proxy type.

Android permits one active VPN service at a time. Disconnect DNSTT before starting OpenVPN, and disconnect OpenVPN before starting DNSTT.

## Important protocol boundary

The current code does **not** implement SOCKS5 UDP ASSOCIATE, generic UDP forwarding through DNSTT/SSH, QUIC tunneling, or QUIC-over-SOCKS5 end-to-end. These cannot be made functional by adding a menu item alone: they need packet-preserving transport and compatible remote-side handling. See [PROTOCOL_SUPPORT_AND_GAPS.md](PROTOCOL_SUPPORT_AND_GAPS.md) for the audited support matrix and acceptance tests. Unsupported transports must not be presented as working.

## Current UX

- Dark green/cyan Android interface with animated Matrix-style background.
- Home dashboard includes connection state, profile controls and traffic statistics/chart.
- OpenVPN profile import, profile selection/deletion, advanced config editor and transport settings.
- Per-profile OpenVPN username/password persistence in app-private storage.
- In-app logs screen with live refresh, copy/clear actions and OpenVPN connection diagnostics.
- Connection engine selection from the toolbar/side menu; no separate OpenVPN button on the home screen.
- ARM64 APK build artifact from GitHub Actions.

## Build an APK

1. Open the [Build DNSTT Ghosts APK workflow](.github/workflows/build-apk.yml).
2. Choose **Run workflow** or push a commit to `main`.
3. Wait for the workflow to finish successfully.
4. Download the artifact named **DNSTT-Ghosts-ARM64-APK** from that run.
5. Install the APK on an ARM64 Android device. If updating an existing installation, Android may require the same signing key as the installed build.

The workflow verifies the pinned OpenVPN engine archive checksum, applies the project's patches to the source archive, validates Android resources, builds the debug APK and uploads the APK as an artifact.

## OpenVPN profile troubleshooting

- Import the client `.ovpn` file. If it references separate text `.crt`, `.pem`, `.key` or similar files, select those files in the same import operation.
- Binary `.p12/.pfx` bundles and external CRL files are not automatically converted; export their certificate/key material in a supported text/inline format first.
- Confirm that the server address, port, protocol (UDP/TCP), certificates and credentials match the server configuration.
- TCP port 443 is not a guaranteed DPI bypass and will only work if the server is configured for that transport and port.
- Review the in-app Logs screen for authentication, TLS, DNS resolution, connection-refused and timeout diagnostics.
- A successful compile does not prove a profile can connect to a particular server; verify with a valid server and profile.

## Security notes

- OpenVPN credentials are stored in app-private storage for reuse on that device. Do not share profile exports that contain credentials, certificates or private keys.
- The app should never print private keys or passwords into diagnostic logs.
- If a private key was shared publicly, revoke/rotate it and issue a replacement.
- Do not use fake domain names or a cosmetic speed setting as a substitute for a reachable server. Tunnel throughput depends on the server, network, DNS path and MTU.

## Third-party components and licenses

The build uses the pinned OpenVPN engine dependency and the project's DNSTT native AAR. Review the relevant upstream licenses and notices before redistributing binaries or modified source. In particular, comply with the license and source-distribution obligations of the selected OpenVPN engine and any bundled dependencies. See `OPENVPN-NOTICES.md` in the generated Android project when present.

## Known verification boundary

A successful compile and a live connection are separate checks. Test the produced APK on a real Android device against known-good DNSTT and OpenVPN endpoints before treating the connection paths as verified.
