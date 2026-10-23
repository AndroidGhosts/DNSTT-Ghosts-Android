# Protocol support and implementation boundary

This document describes what the current client code actually implements. A UI label is not proof that a transport works end-to-end.

## Implemented in the current source

| Feature | Implementation path | Scope |
|---|---|---|
| DNSTT over DNS/UDP | `core/engine/tunnel.go` and the bundled DNSTT client | DNS packet transport to a configured resolver |
| DNSTT over DoH | `client.NewDoH` | DNS messages over HTTPS |
| DNSTT over DoT | `client.NewDoT` | DNS messages over TLS |
| KCP + Noise + smux | `Tunnel.buildStack` | Reliable multiplexed streams over the DNSTT packet connection |
| Local SOCKS5 proxy | `core/engine/socks.go` | TCP CONNECT; SOCKS5 UDP ASSOCIATE is not implemented |
| Local HTTP proxy | `core/engine/socks.go` | HTTP proxy requests through tunnel streams |
| SSH mode | `core/engine/ssh.go` and tunnel mode handling | SSH authentication/forwarding; not a generic UDP tunnel |
| Local TCP port forwarding | `serveForward` | TCP forwarding |
| OpenVPN | workflow-integrated pinned OpenVPN engine and app UI | Requires a valid server profile and credentials |

## Not implemented end-to-end yet

- SOCKS5 UDP ASSOCIATE and UDP datagram forwarding.
- QUIC as a tunnel transport or QUIC-over-SOCKS5.
- Generic UDP forwarding through DNSTT or SSH.
- SSH-over-UDP. Standard SSH is TCP; UDP needs a separate encapsulation/remote endpoint.
- App-specific split routing is an Android VPN routing feature, not a property of SOCKS5 or DNS transport selection.

## Why the missing items cannot be enabled by a UI-only patch

UDP datagrams require a packet-preserving path and matching server-side behavior. The current DNSTT engine exposes multiplexed byte streams; sending arbitrary UDP payloads into a TCP stream without a defined framing protocol and compatible server would not provide real UDP support. QUIC also needs a usable UDP path and a compatible QUIC endpoint. A client-only toggle would therefore be misleading.

## Required acceptance tests for a real implementation

1. Unit tests for SOCKS5 UDP ASSOCIATE negotiation, address parsing, datagram framing, fragmentation handling, timeouts and close behavior.
2. Integration tests against a matching remote UDP relay, including DNS and non-DNS UDP.
3. QUIC handshake and bidirectional stream tests through the chosen transport, with loss/reconnect coverage.
4. SSH TCP forwarding tests against a real SSH server; do not label it SSH-over-UDP.
5. Android instrumentation tests confirming the selected profile reaches the engine and traffic counters update.
6. A successful ARM64 APK build plus on-device tests against known-good endpoints.

Do not mark unsupported rows as available until both client and required remote components pass the relevant tests.
