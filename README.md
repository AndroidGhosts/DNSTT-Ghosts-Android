# Android_Ghosts — DNSTT

A phone-first Android DNSTT client branded **Android_Ghosts**, intended for ARM64 Android devices such as the Samsung Galaxy A56.

## Scope

- **DNSTT only** — uses the project's bundled `ghostcore.aar`.
- No OpenVPN engine, `.ovpn` importer, OpenVPN settings, OpenVPN diagnostics, or OpenVPN-specific dependencies are included in this build.
- The app keeps its dark green/cyan Android interface, animated Matrix-style background, profile controls, traffic chart, latency test, and diagnostic log screen.

## Build an APK

1. Open the [Build DNSTT Ghosts APK workflow](.github/workflows/build-apk.yml).
2. Choose **Run workflow** or push a commit to `main`.
3. Wait for the workflow to finish successfully.
4. Download the artifact named **DNSTT-Ghosts-ARM64-APK** from that run.
5. Install the APK on an ARM64 Android device. If updating an existing installation, Android may require the same signing key as the installed build.

The workflow applies the project's DNSTT and Android_Ghosts UI patches, validates Android resources, builds the ARM64 APK, and uploads the APK as an artifact.

## Security

- Do not share profiles that contain passwords, private keys, or other secrets.
- Diagnostic logs should never contain private keys or passwords.
- Use a real, reachable DNSTT server and valid settings; a cosmetic speed setting or fake domain name does not create extra bandwidth.

## Verification boundary

A successful compile confirms that the APK builds; it does not prove that a particular DNSTT server is reachable. Test the resulting APK on the target Android device.
