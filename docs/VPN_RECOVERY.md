# Persistent VPN recovery

Connect records device-local desired state before starting the foreground service.
An atomic journal in `no_backup` stores a signed generation under a process-shared
file lock. Explicit Stop records a newer negative generation. Configuration
exports and imports do not transfer this runtime intent to another device.

Startup errors keep the service requested on. Retries use 2, 4, 8, 16, 32 and
60 seconds, with up to 999 ms jitter, and no attempt limit. One stable minute
resets the backoff. The notification displays the wait; Control API `status`
exposes `desiredConnected`, `retryAttempt` and `retryAtMs`. A missing plugin is
reported once and retried at the capped interval so it can recover after install.

Lifecycle cleanup remains serialized with startup. Delayed attempts, old process
callbacks and notification actions cannot override a newer explicit Stop. A new
Connect while earlier cleanup drains is fulfilled after that cleanup. Native
resources are disposed before their replacement starts.

For recovery and internal restarts, the service retains its Android TUN descriptor
when the configuration revision and effective TUN options match. Each native
instance owns a duplicate descriptor. Generated interface names and descriptor
numbers are excluded from the reuse comparison. Explicit Stop, permission revoke,
configuration changes requiring a different TUN, and service destruction release
the descriptor. Retaining it prevents a temporary native restart from silently
returning routed traffic to an unprotected default route.

WireGuard endpoint initialization runs in an endpoint-owned, cancellable worker.
A failed peer lookup or endpoint startup retries independently, with the same
capped backoff. Failed candidates are closed before rebuilding. Existing allowed
prefixes remain owned by that endpoint while unavailable; its traffic fails
closed instead of falling through another route. Other outbounds keep working.
Close cancels a blocked DNS lookup and joins the worker, preventing late retries.
WireGuard handshake/rekey behavior after successful initialization stays in the
WireGuard implementation.

`START_STICKY` and the persisted intent let Android reconstruct the VPN service
after a process crash. Pixel acceptance showed that sticky restart alone can leave
a dead service record with no new process. An OS-owned inexact alarm therefore
checks this app's own service once per minute while desired on. Its receiver runs
in the default process, independently of the native `:bg` process, and requests a
restart only if that service has no live process. It performs no network tests
and requests no exact-alarm permission. Android can defer inexact alarms during
idle; it is a safety net rather than a strict restart deadline. Explicit Stop
cancels it and its receiver checks the current intent generation again.

A process death still destroys its open descriptors and causes an interruption
until recovery. This is not a guarantee against
Android force-stop, permission removal, another VPN taking ownership, or system
restrictions. System Always-on/lockdown settings are not changed by this feature.

## Verification

- JVM tests cover bounded retry delays, stale generations and TUN reuse policy.
- Device tests exercise atomic-journal reconstruction, interrupted writes,
  corruption and concurrent instances in isolated temporary directories.
- Native race tests inject SERVFAIL, demonstrate that independent TCP traffic
  remains available, restore DNS and pass HTTP over two real WireGuard peers.
  A second test closes the core while the resolver is blocked. The first test
  reproduces the global startup failure against the previous implementation.
- Physical-device acceptance uses debug-only app-private startup fault injection,
  explicit Stop during retry, rapid Stop/Connect, process termination and actual
  Wi-Fi/cellular handovers. The failpoint is inert in release builds and is not
  exposed through intents or configuration.

Private configuration, device identity, traffic diagnostics and backups belong
outside Git. In-place updates require matching signing certificates; do not
uninstall the configured app to work around signing errors.
