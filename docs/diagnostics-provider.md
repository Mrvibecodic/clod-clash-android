# Diagnostics provider contract

Clod Clash uses the official Chisel server as a transport. The Android client
cannot enforce account isolation on that server, so a provider deployment MUST
apply the following contract before issuing diagnostics credentials.

## One device, one account, one reverse port

Issue every device its own:

- Chisel username and password;
- reverse TCP port in `1024..65535`;
- Mihomo controller secret, distinct from the Chisel password.

Do not reuse any of these values between devices. The reverse listener is
always requested as:

```text
R:0.0.0.0:<assigned-port>:127.0.0.1:9090
```

The Chisel server must run with `--reverse` and `--authfile`. Restrict each
account to its assigned container listener using an anchored address regex:

```json
{
  "device-example:[REPLACE_WITH_DEVICE_PASSWORD]": [
    "^R:0[.]0[.]0[.]0:19091$"
  ]
}
```

The regex is matched against Chisel's `Remote.UserAddr()`, which omits the
client-side target. The app fixes that target to `127.0.0.1:9090`; the server
ACL fixes the server-side bind address and port.

Never use a shared `--auth` credential, an empty allow regex, a wildcard
reverse rule, or a host-published reverse port. Chisel and Caddy must share a
Docker network; Caddy reaches the assigned listener by the Chisel container
name and port. `0.0.0.0` is scoped to the Chisel container namespace,
while the private Mihomo target remains fixed to `127.0.0.1:9090` on the phone.
A provider route to MetaCubeXD must map only the intended device to its assigned
container port.

## Public controller route

The app verifies end-to-end readiness with an authenticated HTTPS request to
`/controller/version`. The public route MUST therefore be path-scoped, protected
by the provider's edge ACL and rate limit, and sent directly to the assigned
Chisel container port:

```caddyfile
handle_path /controller/* {
    import provider_ip_acl
    import provider_rate_limit
    reverse_proxy metacubexd-tunnel:<assigned-port>
}
```

Place this handler before the generic MetaCubeXD UI handler. Do not redirect the
controller path, send it to another origin, or use a catch-all controller proxy.
Keep Caddy's credential logging disabled: never enable `log_credentials`, and do
not add an access-log field that records the `Authorization` request header. The
app follows no redirects, verifies TLS, sends the controller secret only as a
Bearer header, and exposes only ready/failed state to the UI.

The full Mihomo controller API is available through an active route. Protect
the provider route, disclose this access to the user, and remove the account
and route when support access is revoked.