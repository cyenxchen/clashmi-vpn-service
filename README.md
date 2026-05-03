# clashmi_vpn_service

Open VPN bridge for Clash Mi.

## Scope

This package is the replacement bridge for the previous closed VPN service
package. The first milestone is Android-only:

- keep Clash Mi's Dart-facing VPN API compatible;
- bind the `cyenxchen/mihomo` Go core with `gomobile`;
- implement Android `VpnService` startup, TUN open, traffic polling, and stop;
- keep iOS source layout available for a later `NEPacketTunnelProvider` port.

The current native Android `start` method is intentionally a stub. It returns a
clear error until the Go AAR and real TUN bridge are wired.
