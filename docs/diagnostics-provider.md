# Diagnostics provider contract

Clod Clash uses the public
[`krolbot/metacubexd-tunnel`](https://github.com/krolbot/metacubexd-tunnel)
container for authenticated bootstrap, Chisel transport, MetaCubeXD and
per-client controller routing.

## Client credentials

Issue every device its own:

- username;
- password.

The app stores only these credentials in its Android Keystore-backed encrypted
store. The server URL remains a separate user setting.

On every diagnostics enable, the app authenticates to `GET /api/v1/session`.
The server returns a persistent per-client reverse port and Controller secret.
The app does not display or persist those generated values. It applies the
Controller secret to the active loopback Mihomo controller and requests:

```text
R:0.0.0.0:<server-assigned-port>:127.0.0.1:9090
```

Bootstrap and Chisel use the same username/password. The server constrains the
Chisel account to its assigned reverse listener.

## Support access

Support selects a client by username and obtains its generated Controller
secret locally from the server runtime. MetaCubeXD connects to:

```text
https://<provider-host>/controller
```

with that Controller secret as Bearer authentication. The secret is for access
to the selected phone's Mihomo controller; it is not the Chisel password.

## Provider deployment

Use the published image:

```text
ghcr.io/krolbot/metacubexd-tunnel:latest
```

The container exposes one HTTP port containing:

- Chisel WebSocket ingress;
- authenticated bootstrap API;
- MetaCubeXD UI;
- per-client controller proxy.

A reverse proxy therefore needs only one upstream:

```caddyfile
support.example.com {
    import provider_ip_acl
    import provider_rate_limit
    reverse_proxy metacubexd-tunnel:8080
}
```

WebSocket forwarding must preserve `Sec-WebSocket-Protocol`. Do not log
`Authorization`, the bootstrap response, usernames/passwords or Controller
secrets.

The full Mihomo controller API is available while diagnostics is enabled.
Protect the provider edge, disclose this access to the user, and remove the
client account when support access is revoked.
