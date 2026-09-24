## Privacy Policy

Clod Clash is an open source VPN client. It is provided free of charge and as is.

The app has no accounts of its own, shows no advertising, and contains no analytics or
crash-reporting code. Everything described below covers the requests this app makes itself.

**What the app sends, and where**

*   **To the subscription address you entered.** When the app downloads or refreshes a
    subscription, it requests the URL you entered. If the profile uses the secure
    channel, the request to that same address is sent with a browser-like TLS fingerprint and
    a browser-like set of headers (a desktop Chrome `User-Agent`, `sec-ch-ua`,
    `accept-language` and similar) instead of the app's own `User-Agent`; the host is still
    the one you entered and no relay of ours is involved; in that mode the four values below
    travel encrypted inside the request path rather than as headers. If the "device identifier"
    setting is enabled, the request carries four headers: `x-hwid`, `x-device-os`
    (`Android`), `x-ver-os` (the Android version) and `x-device-model` (manufacturer and
    model). `x-hwid` is a pseudonymous identifier — a truncated SHA-256 hash of the system
    `ANDROID_ID` with a fixed salt, or of a locally generated random value if `ANDROID_ID`
    is unavailable. It is not a hardware serial number. The salt is a constant published in
    the source, so the value is stable and identical across providers: a provider cannot
    recover `ANDROID_ID` from it in practice, but two providers who compare notes could tell
    that two subscriptions belong to the same device.
    Turning the setting off removes all four headers. These headers exist so that a provider
    can enforce its own device limits; whether a provider stores them is the provider's
    decision, described in the provider's own policy.
    One more thing to know about this request: while the VPN is on, it normally travels
    through the tunnel like any other traffic. If that attempt fails — the selected server
    is down, or the connection through it breaks — the app retries that request outside the
    tunnel, directly from your network. In that case the provider sees your real IP address
    together with the device headers above. Every failed attempt is retried directly at most
    once, and one refresh can make several attempts: the address you entered plus up to two
    spare addresses supplied by the provider, and, with the secure channel, up to three
    rounds per address. So a single refresh can produce up to three such direct requests, or
    up to nine when the secure channel is in use; the time budget of the refresh may cut that
    short. The retry is made only for the subscription address, never for other hosts, and
    only after the attempt through the tunnel has failed.
*   **To a connectivity-check address, through each server.** Measuring latency (the
    "check" button, the automatic check when the Servers tab is opened, and the checks
    after a network change) sends a tiny request to a test address through every server
    being measured. The address is the one set in your subscription for that group or
    provider; when none is set, the app uses `https://www.gstatic.com/generate_204`,
    operated by Google. The request carries no identifier and no device headers, but the
    operator of the test address sees a connection from each server's IP.
*   **DNS.** Name resolution goes wherever your configuration says. If the configuration
    leaves DNS disabled — either because it has no DNS section at all, or because the
    section is present but sets `enable: false` — the app replaces that whole section with
    one of its own: DNS is turned on, any resolvers the configuration listed are discarded,
    and public resolvers of the app's choosing are filled in — currently `1.0.0.1`
    (Cloudflare), `8.8.4.4` (Google) and `9.9.9.10` (Quad9) — so the names you resolve reach
    those operators. Your own DNS override, if you set one, replaces that list. In this
    branch the app also appends the resolver handed out by your network or mobile operator,
    so the names you resolve reach that resolver too; the same appending happens whenever
    the configuration itself asks for the system resolver. If the configuration enables DNS
    on its own, the app leaves the section as written.
*   **To the update and routing-data endpoints.** Checking for an app update, downloading an
    update package, fetching routing databases (GeoIP, GeoSite, ASN) and loading a provider
    logo send only a standard `User-Agent` of the form `ClodClash/<version> (Android)`. No
    device headers and no identifier are attached to these requests.
*   **Through the tunnel itself.** While the VPN is on, application traffic goes to the proxy
    servers listed in your subscription. The app does not inspect, store or forward that
    traffic anywhere else; where it ends up is defined by the configuration you supplied.

Nothing else is transmitted. The app has no server of its own.

**What stays on the device**

Subscriptions, profiles, configuration files, credentials contained in them, selected
servers, settings and logs are stored in the app's private storage. The app itself never
uploads them anywhere; the one way they can leave the device is system backup, described
below. The
log screen and saved log files are written locally; they are shared only if you export and
send them yourself. Note that logs and configuration files can contain your subscription
address, so review a log before sharing it.

System backup is currently enabled for the app (`allowBackup`), and the backup rules include
the profile database, imported and pending profile directories, the config overrides and the
app's settings (which contain the locally generated fallback identifier when `ANDROID_ID` was
unavailable). No separate
Android 12+ extraction rules are declared, so on newer versions the default set — which is
wider than that list — applies. On a device
where cloud backup is on, those files — including subscription addresses and any credentials
inside the configuration — are copied by the operating system to the backup provider you use.
That transfer is performed by the operating system, not by this app, but it does mean the data
can leave the device. Turn off backup for the app in system settings if that is not wanted.

**Permissions**

The VPN permission is required to create the tunnel; notification permission is used for the
foreground service notification and for subscription and update notices. The app also declares
`QUERY_ALL_PACKAGES`, because the per-app tunnel screen has to list the applications installed
on the device — that list is read locally and never sent anywhere; `REQUEST_INSTALL_PACKAGES`,
to install an app update it has downloaded; and the battery optimisation permission, used for
scheduled subscription refreshes.
The app requests no camera, location, contacts or microphone access.

**Children's privacy**

The app is not directed at children under 13 and collects no personal information from
anyone, including children.

**Links to other sites**

The app can open links supplied by your subscription provider — a support page, a portal or a
provider announcement. Those sites are not operated by the authors of this app and have their
own policies.

**Security**

Subscription addresses entered in the app must be HTTPS, and update packages are verified by
SHA-256 and against the signature of the installed package before installation. Two honest
caveats: a configuration can itself point at plain `http` addresses for its rule and proxy
providers, and those are then fetched without TLS; and the network security configuration
trusts user-installed root certificates, so a certificate added to the device (by you, by an
employer's device management, or by a debugging proxy) can read subscription traffic. No method of transmission
or storage is completely secure, so absolute security cannot be guaranteed.

**Changes to this policy**

This page is updated when the behaviour of the app changes. The version in the repository
always describes the current release.

**Contact**

Questions and reports: open an issue in the project repository.
