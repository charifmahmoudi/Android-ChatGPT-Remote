# Test plan

## Purpose

This plan defines the automated and manual testing needed to keep Android ChatGPT Remote safe,
reliable, and suitable for community contributions.

The project joins three boundaries that have different test characteristics:

1. the Secure MCP Tunnel protocol;
2. the Android activity and foreground-service lifecycle;
3. same-device Wireless ADB and OEM-specific Android behavior.

CI should cover deterministic behavior without production credentials. Emulator smoke tests should
cover Android framework integration. A physical device remains the release gate for Wireless
Debugging pairing, background execution, and OEM behavior.

## Goals

- Detect protocol, JSON-RPC, concurrency, retry, and deadline regressions quickly.
- Verify the application installs, launches, and follows Android foreground-service requirements.
- Exercise the dashboard, configuration flow, notification, and service state transitions.
- Test tunnel and ADB failures without contacting production services.
- Exercise the real Kadb-to-adbd path on an emulator where it is reliable.
- Prevent credentials, commands, device output, and other sensitive values from entering logs or
  test artifacts.
- Keep pull-request checks reproducible for contributors and forks.

## Non-goals

Normal pull-request CI does not attempt to prove:

- Samsung split-screen pairing behavior;
- behavior of every Android OEM or firmware version;
- survival under OEM battery-management policies;
- production Secure MCP Tunnel availability;
- the security of an arbitrary ADB shell command;
- release signing or Play Store distribution.

These require protected integration testing or physical-device validation.

## Test environments

| Environment | Purpose | Trigger | Required |
| --- | --- | --- | --- |
| Host JVM | Fast protocol and application-logic tests | Every push and pull request | Yes |
| Android emulator | Framework, UI, storage, service, and notification tests | Every pull request | Yes |
| Real-emulator ADB smoke test | Kadb interoperability with Android adbd | Main branch or scheduled | Initially non-blocking |
| Protected live tunnel | End-to-end tunnel interoperability | Manual or scheduled | No |
| Physical Android device | Pairing, lifecycle, networking, and OEM validation | Before a release | Yes |

Start with one representative API 35 or API 36 emulator. Once the suite is stable, add a periodic
matrix containing the minimum practical Wireless Debugging version (API 30) and the current target
API. Avoid running a large emulator matrix on every pull request.

## Pull-request test suite

### 1. Build and static analysis

Run:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The job must fail on compilation, unit-test, lint, or APK assembly failure. Upload test and lint
reports when the job fails. The debug APK may be uploaded for maintainer inspection, but it is not a
release artifact.

### 2. JVM unit tests

Keep external services and Android framework behavior out of this layer. Cover at least:

#### Tunnel protocol

- Canonical poll and response paths.
- Authentication and diagnostic headers.
- Successful `200` command and `204` no-content polls.
- Tunnel `401` and `403` authorization failures.
- Retryable poll failures: network errors, `429`, and `5xx`.
- Retryable response failures: transport errors, `408`, `429`, `502`, `503`, and `504`.
- Terminal response `404`.
- Unknown command types and unknown JSON properties.
- Request ID, channel, and shard-token correlation.
- Missing response bodies and malformed envelopes.

#### Deadlines and concurrency

- Valid and invalid `response_timeout` values.
- Expiry before dispatch while a command waits in the queue.
- Expiry during command execution.
- Queue-capacity behavior.
- A maximum of four concurrent command workers.
- Isolation of one command failure from polling and unrelated workers.
- Cancellation during reconfiguration and service stop.
- Bounded exponential backoff.

Tests involving time or randomness should use injected clocks, delay strategies, or deterministic
random sources rather than wall-clock sleeps.

#### MCP behavior

- `initialize`, `tools/list`, and supported notification behavior.
- Schemas for `adb_status`, `adb_shell`, `adb_packages`, and `adb_properties`.
- Invalid JSON-RPC and invalid tool arguments.
- Unknown tools and methods.
- Output-size and command-size limits.
- ADB exceptions returned as bounded, privacy-safe tool errors.
- Session termination.

#### Configuration and diagnostics

- Tunnel ID, port, PIN, and loopback-host validation.
- Diagnostic ring-buffer rotation.
- Safe exception-class chains and low-cardinality failure categories.
- Absence of runtime keys, tunnel/request/shard identifiers, pairing values, commands, URLs,
  payloads, exception messages, and ADB output from exported diagnostics.

### 3. Emulator instrumentation tests

Place tests under `app/src/androidTest/` and run them with `AndroidJUnitRunner`. These tests may
use ActivityScenario, Espresso, UI Automator, and Android service-testing utilities as appropriate.

#### Manifest and installation

Verify:

- the APK installs and launches;
- backup and cleartext traffic are disabled;
- `TunnelService` is not exported;
- the required foreground-service permissions and `specialUse` type are declared;
- the launcher activity is available.

Some manifest properties are also suitable for fast host-side assertions.

#### Dashboard and input handling

Verify:

- first launch shows the tunnel-configuration state;
- invalid tunnel credentials remain in `NEED_TUNNEL`;
- valid test credentials advance to the expected next state;
- invalid pairing PINs and ports are rejected;
- invalid ADB connection ports are rejected;
- runtime-key and pairing-PIN fields are cleared after submission;
- only controls relevant to the current phase are visible;
- activity recreation preserves the correct presentation;
- version information is displayed;
- copying diagnostics produces a bounded report.

Do not take screenshots containing test credentials, even when the values are synthetic.

#### Secure configuration

Verify on a real Android runtime:

- configuration can be saved and loaded;
- configuration survives activity recreation and process restart;
- clearing application data removes it;
- the runtime key is not stored in ordinary unencrypted SharedPreferences;
- sensitive fields are not exposed in logs, notifications, summaries, or broadcasts.

Tests should use synthetic values that cannot authorize a real tunnel.

#### Foreground service and notification

Verify:

- launching the activity starts the foreground service;
- a notification channel and persistent notification are created;
- tapping the notification opens the dashboard;
- closing the activity does not immediately stop the service;
- Stop cancels work, updates state, and stops the service;
- Retry reevaluates configuration;
- repeated start requests are safe;
- notification text follows service state;
- notification-permission behavior is correct on API 33 and later.

Avoid asserting exact lifecycle timing where Android only guarantees eventual behavior. Poll for a
bounded condition instead of using fixed sleeps.

## Deterministic emulator integration

The production service currently constructs its tunnel client and ADB transport directly. Introduce
small dependency boundaries before adding this suite:

- a tunnel-client factory;
- an MCP/ADB transport factory;
- a configurable base URL for debug and test builds;
- injectable clock, delay, and backoff behavior where required.

Production defaults must continue to use the real Secure MCP Tunnel endpoint, Kadb transport, and
system time. Test overrides must not be reachable from a release build.

Use a local mock HTTP server and a fake ADB transport to exercise the complete application state
machine:

1. Start without configuration and assert `NEED_TUNNEL`.
2. Submit synthetic credentials and simulate successful pairing.
3. Save an ADB endpoint and simulate a successful probe.
4. Return a successful tunnel poll and assert `RUNNING`.
5. Deliver `tools/list` and `adb_status` commands.
6. Verify the correlated response body and headers.
7. Interrupt polling and assert reconnecting behavior.
8. Restore polling and assert recovery.
9. Fail an ADB operation and assert that `RUNNING` is removed immediately.
10. Return authorization, retryable, malformed, unsupported, and expired commands and assert their
    documented outcomes.

This suite is the primary end-to-end pull-request gate because it is deterministic and uses no
external credentials.

## Real-emulator ADB smoke test

A separate job may attempt to validate the actual Kadb-to-adbd path:

1. Boot an Android 11+ emulator.
2. Configure an isolated emulator ADB endpoint.
3. Install the debug APK.
4. Establish the test ADB identity using a CI-only procedure.
5. Run only:
   - `adb_status`;
   - `getprop ro.product.model`;
   - package listing;
   - a harmless command such as `echo ci-smoke-test`.
6. Verify bounded results and health transitions.
7. Destroy the emulator and all generated ADB keys.

Never execute contributor-supplied shell text in this job. Do not expose ADB outside the CI runner.

Emulator adbd and networking differ from a physical device. Keep this job non-blocking until it has
demonstrated acceptable stability. If it remains flaky, run it on the default branch or a schedule
and retain the deterministic integration suite as the pull-request gate.

## Protected live-tunnel test

A live Secure MCP Tunnel test is optional and must not run for pull requests from forks. Run it only
through a protected GitHub Environment on `workflow_dispatch` or a trusted scheduled workflow.

Requirements:

- use a dedicated test tunnel;
- use a narrowly scoped, revocable runtime key;
- restrict execution to trusted default-branch code;
- mask secrets and prohibit credential output;
- allow only predefined, harmless MCP operations;
- apply strict job and command timeouts;
- upload only privacy-safe diagnostics;
- rotate the runtime key after suspected exposure.

The test should demonstrate one complete sequence: poll, receive a safe command, execute it against
the test transport or emulator, post the correlated response, and terminate cleanly.

## Physical-device release validation

Before publishing a release, test on at least one Android 11+ physical device. Prefer one confirmed
Samsung device plus a second OEM when available.

Validate:

- clean installation and notification permission;
- Samsung split-screen pairing using a fresh PIN;
- separation of the temporary pairing port and connection port;
- first successful ADB probe;
- live tunnel connection and `RUNNING` state;
- all four MCP tools, with a harmless reviewed `adb_shell` command;
- activity closure while the service continues;
- explicit Stop and subsequent restart;
- wrong PIN and expired PIN recovery;
- invalid and rotated ADB connection ports;
- disabled and re-enabled Wireless Debugging;
- Wi-Fi change and temporary network loss;
- wrong or revoked tunnel credentials;
- process recreation;
- device reboot;
- OEM battery restrictions;
- diagnostic export and manual secret inspection.

Record the device model, Android version, OEM build, application version, and result. Never record
credentials, pairing data, commands containing private information, or device output.

## Flakiness policy

- A required test must be deterministic or have a documented Android-platform reason for bounded
  polling.
- Do not hide failures with unconditional `continue-on-error`.
- Quarantine a flaky test only with a linked issue, owner, and removal condition.
- Preserve logs, test reports, and emulator diagnostics on failure, after applying privacy rules.
- Retry infrastructure startup at the job level only when failure occurred before application tests.
- Do not automatically retry failed behavioral assertions, because that conceals regressions.

## CI security rules

- Pull-request tests must require no production secrets.
- Forked code must never run with repository or environment secrets.
- Test credentials must be obviously synthetic.
- Do not print intents, SharedPreferences, clipboard contents, HTTP authorization headers, ADB keys,
  or raw device logs.
- Pin third-party CI actions to reviewed versions or commit SHAs.
- Grant workflow jobs the minimum GitHub token permissions, normally `contents: read`.
- Destroy emulator data and generated keys at the end of protected tests.

## Coverage expectations

Code coverage is diagnostic, not the release criterion. Prefer coverage of important behavior and
failure modes over a repository-wide percentage target.

The following paths must have direct tests:

- tunnel authorization and retry decisions;
- response deadlines and worker isolation;
- every MCP method and tool;
- service state transitions;
- ADB health loss and recovery;
- validation of all credential and port inputs;
- secret-redaction rules;
- foreground-service start and stop behavior.

## Initial implementation milestones

1. Configure `AndroidJUnitRunner` and create a minimal emulator CI job.
2. Add launch, manifest, configuration, notification, and service smoke tests.
3. Introduce injectable tunnel and ADB factories without changing production behavior.
4. Add deterministic application-level integration tests with a mock tunnel and fake ADB transport.
5. Add API 30 and target-API scheduled coverage.
6. Experiment with a non-blocking real-emulator ADB smoke test.
7. Add a protected live-tunnel workflow only if its maintenance value justifies secret management.

## Release gate

A release candidate is acceptable when:

- all required JVM and emulator tests pass;
- lint and APK assembly pass;
- no unresolved high-severity security regression exists;
- the physical-device checklist passes;
- documentation matches observed setup and recovery behavior;
- the APK is built and signed through the approved release process.
