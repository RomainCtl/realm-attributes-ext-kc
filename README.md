# Keycloak extension — Realm attributes UI tab

A small Keycloak provider that adds an **Attributes** tab to the **Realm settings**
page of the `keycloak.v2` admin console, letting realm administrators read and edit
the realm's attributes (the `REALM.ATTRIBUTE` column) from the UI instead of the
Admin REST API.

It is the Keycloak 26.x equivalent of the legacy AngularJS admin-theme extension —
the same idea, implemented on top of the new declarative-UI SPI.

## Which version for which Keycloak?

| Keycloak version | Use |
|---|---|
| **<= 20** | **v1.0** — `tag v1.0`, the AngularJS admin-theme extension |
| **21 → 25** | None of the two: the legacy admin theme is being phased out and the `declarative-ui` SPI is not yet available. Stick with the Admin REST API, or pick the closest of the two versions. |
| **>= 26** | **v2.0** — this repository |

If you are on Keycloak 26 and only the legacy admin theme is enabled at startup, the
new tab will not appear — make sure you start the server with
`--features=declarative-ui` (see [Requirements](#requirements)).

> Note: `UiTabProvider` exist since keycloak v24.x, this extension may work for this version, but it hasn't been tested. Feel free to create an issue or a PL if needed.

## How it works

- Implements `org.keycloak.services.ui.extend.UiTabProvider` /
  `UiTabProviderFactory` (experimental, gated by the `declarative-ui` feature).
- The provider declares a single `MAP_TYPE` config property (the built-in
  `MapComponent`: an add/remove key-value row editor), so the form is fully
  declarative — **no custom theme, no custom REST endpoint**.
- Saving the form goes through the standard components REST API. The factory's
  `onCreate` / `onUpdate` callbacks (the latter receives both the old and the new
  component model) mirror the submitted rows to `RealmModel.setAttribute` /
  `RealmModel.removeAttribute`. Because `onUpdate` sees both models, **deletions
  are properly applied** to the realm attributes.
- **Pre-fill is automatic and one-shot.** When this extension is installed,
  every realm — existing (seeded at startup by `postInit`) and newly created
  (seeded on `RealmPostCreateEvent`) — gets an `attributes` component
  pre-populated from its current realm attributes. Internal Keycloak
  attributes (conventionally prefixed with `_`, e.g. `_browserlessFlow`) and
  the reserved route-param names (`realm`, `tab`) are filtered out and never
  shown in the form. If the tab is opened before the seed has run (rare), or
  if the admin saves the form empty on first creation, the factory also seeds
  the stored config on that first save so the tab is not blank.
- After this first sync, **the stored component config is the source of truth
  for the form**: any attribute added, edited or removed outside this tab (REST
  API, scripts, another extension) will not appear in the form until you save
  it again. Keycloak 26.x does not publish an event for arbitrary
  `RealmModel.setAttribute` calls, so the form cannot auto-refresh from
  external writes. To pick up attributes added later, save the form again from
  the tab (an empty save does not wipe the realm attributes — it re-seeds
  from them).

## Requirements

- **Keycloak 26.7.x** (`quay.io/keycloak/keycloak:26.7.4` or newer 26.x).
- The `declarative-ui` feature **must be enabled** at server start (it is
  experimental in 26.x — the official way to add tabs to the v2 console).
  ```bash
  start-dev --features=declarative-ui
  # or, for optimized images built with `kc.sh build`:
  ENV KC_FEATURES=declarative-ui
  ```

> /!\ Warning, **`declarative-ui` is experimental** — the only official way \
  to extend the keycloak.v2 admin console with a tab today.

## Usage

1. Open the admin console and select a realm.
2. Click **Realm settings** → **Attributes** (the new tab, between the existing
   ones).
3. Add, edit or remove key/value pairs and click **Save**.
4. The realm attributes are updated immediately; reverting or deleting the
   component (Components → your entry) cleans up the keys it manages.

## Makefile targets

```
Usage:
  make <task>

Project task
  build            Build docker image from source
  run              Start the container
  logs             Display logs from the running container
  stop             Stop the container
  rm               Destroy the container

Internal services starting tasks
  dev              Start 'keycloak' service in dev mode (mounts providers from ./target so a `mvn package` is enough to reload)
  kc               (Re)start 'keycloak' service only (also available: 'kc-[build|logs|sh|stop|rm]')
  db               (Re)start 'postgres' service only (also available: 'db-[build|logs|sh|stop|rm]')

Commons basics tasks
  bash             Open a new bash session
  help             Display this help
```
