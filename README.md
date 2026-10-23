# Android_Ghosts — DNSTT + OpenVPN

A phone-first Android VPN client branded **Android_Ghosts**, intended for ARM64 Android devices such as the Samsung Galaxy A56.

## Scope

This project intentionally exposes only two tunnel types:

- **DNSTT** — uses the project's bundled `ghostcore.aar`.
- **OpenVPN** — imports standard client `.ovpn` profiles and supports text certificate/key sidecar files that can be inlined during import.

Android permits one active VPN service at a time. Disconnect DNSTT before starting OpenVPN, and disconnect OpenVPN before starting DNSTT.

## Current UX

- Dark green/cyan Android interface with animated Matrix-style background.
- Home dashboard includes connection state, profile controls and traffic statistics/chart.
- OpenVPN profile import, profile selection/deletion, advanced config editor and transport settings.
- Per-profile OpenVPN username/password persistence in app-private storage.
- In-app logs screen with live refresh, copy/clear actions and OpenVPN connection diagnostics.
- Single tunnel selector in the toolbar menu; OpenVPN is not a separate home-screen protocol button.
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

The repository workflow is designed to build the ARM64 APK, but a successful build and a live OpenVPN connection are separate checks. Test the produced APK on a real Android device with a known-good `.ovpn` profile before treating the connection path as verified.
