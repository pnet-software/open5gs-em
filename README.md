# open5gs-em

The Open5GS side of **TEP** (Telco Exposure & Programmability): one Spring Boot app whose only northbound entrypoint is the TEP API, `/tep-api/v1`. It serves:

- **TEP**: subscribers, real-time status and location, SIM and device swaps, quality profiles and cells, straight from the Open5GS database and network functions.
- **AS sessions with QoS** (3GPP TS 29.122 AsSessionWithQoS), mounted under TEP at `/tep-api/v1/3gpp-as-session-with-qos/v1`. Each session is an app session on the Open5GS PCF.

[camara-tep](../camara-tep) is its client: every CAMARA service there calls this app as its TEP (`TEP_BASE_URL=http://<host>:8080/tep-api/v1`).

> **Trust model.** TEP has no authentication and returns subscriber credentials (K, OPc) in plaintext. Run it behind network-level access control only; never expose it publicly.

## How it works

```
camara-tep services ── /tep-api/v1/subscriber/…, /quality-profile/…, … ──▶ open5gs-em ──▶ Open5GS MongoDB
                                                                                       ──▶ AMF ue-info, SMF pdu-info
SCS/AS ─────────────── /tep-api/v1/3gpp-as-session-with-qos/v1/… ───────▶ open5gs-em ──▶ PCF (Npcf_PolicyAuthorization)

PCF ── /notifications/{scsAsId}/{sessionId}/terminate ──▶ open5gs-em ──▶ SCS/AS {notificationDestination}/notifications
```

| TEP path | Operations | Answered from |
|---|---|---|
| `/subscriber`, `/subscriber/{id}` | list, create, get, update, delete | `subscribers`, the collection the Open5GS WebUI writes |
| `/subscriber/retrieve` | POST a device | as above, found by the device's phone number |
| `/subscriber/status` | POST a device | AMF `ue-info` and SMF `pdu-info`, matched by SUPI (`imsi-<IMSI>`) |
| `/subscriber/location` | POST a device | the NR cell (`nr_cgi`) and its `timestamp` in AMF `ue-info`, with the coverage area from `cells` |
| `/subscriber/swaps` | POST a device | `swap_events`, written by [open5gs-swap-events](https://github.com/pnet-software/open5gs-swap-events). Nothing recorded is a 404: whether the subscriber swapped is unknown. |
| `/quality-profile`, `/quality-profile/{name}` | list, get | the configuration, `tep.quality-profiles` |
| `/cell`, `/cell/{id}` | list, create, get, update, delete | `cells` |
| `/3gpp-as-session-with-qos/v1/{scsAsId}/subscriptions` | create, list | PCF app sessions, tracked in `qos_sessions` |
| `/3gpp-as-session-with-qos/v1/{scsAsId}/subscriptions/{id}` | get, change (PATCH), delete; PUT answers 501 | as above |

The contract is [static/openapi/tep-api.openapi.yaml](src/main/resources/static/openapi/tep-api.openapi.yaml), **TEP 1.0.0**, a copy of [tep-openapi](https://github.com/pnet-software/tep-openapi). Its 3GPP operations are `$ref`s into `TS29122_AsSessionWithQoS_Bundle.openapi.yaml` beside it, and the server is generated from it. Swagger UI at `/` shows it.

### Subscribers

- **Id.** The subscriber id in `/subscriber/{id}` is the `_id` of its Open5GS document, in hex (e.g. `6731f0c2a4b5c6d7e8f90123`): a create answers it in the `Location`, and the list and `POST /subscriber/retrieve` give it too. Updates are made in place, so it stays the same through SIM swaps and number changes. Anything other than 24 hex digits is a 400.
- **Lookups by phone number.** `retrieve`, `status`, `location` and `swaps` take a `TepDevice`, `{"phoneNumber": "..."}`, in a POST body, so phone numbers never appear in a URL, and so in access logs. The number may have a leading `+` or not; both forms find a subscriber whichever way Open5GS stored it. Anything other than up to 15 digits is a 400 naming `/phoneNumber`.
- **Create** writes a subscriber in the shape the WebUI writes, with its defaults: AMBR 1 Gbps, sessions of type IPv4v6 with 5QI 9 and ARP 8, and no PCC rules: an AS session with QoS adds the one it needs. An existing phone number or IMSI is a 409.
- **Update** sets only what TEP owns: IMSI, phone number, K, AMF, OPc and the slices. The IMEISV, which device-swap detection reads, stays, and so do the AMBR and the QoS and PCC rules of every session that still exists. Sessions are matched by (sst, sd, DNN).
  - A different `imsi` is a SIM swap, which open5gs-swap-events records as a swap event.
  - A different `phoneNumber` changes the number: afterwards the lookups find the subscriber by the new number, and the old one is a 404. Its id stays the same.
  - An IMSI or phone number another subscriber has is a 409.
- **Status** reports the UE as the core sees it now:
  - `connectionState` comes from the AMF: `DEREGISTERED` when it has no context for the UE or reports it deregistered (`mm_state`), otherwise `CONNECTED` or `IDLE` from its `cm_state`.
  - `mcc` and `mnc` are the serving network, from the UE's tracking area.
  - `roaming` is whether that network isn't one of the configured home PLMNs, `tep.home-plmns` (see [Configuration](#configuration)). It is absent when the UE has no serving network, or no home PLMNs are configured.
  - `pduSessions` come from the SMF, each with its slice, IP address and QoS flows (`qfi`, `fiveQi`). A slice without an SD, which the SMF reports as `ffffff`, has no `sd`.
- **Location** gives the id of the cell the AMF reports the UE in, its NCGI (PLMN `00101` and NCI 256 is cell `00101-000000100`), and when the UE last reported it (`lastUpdated`). Once that cell is in `cells`, it also gives the cell's coverage area, `center` and `radius`. It is a 404 when the UE isn't registered in an NR cell.

### Cells

- A cell is its PLMN, its NCI in nine hex digits (as 3GPP writes it), and its coverage area: a `center` (`lat`, `lon`) and a `radius` in metres, all required. Its id is the NCGI, MCC and MNC, `-`, then the NCI in lowercase: `99950-000000210`.
- Cells can be added at any time, for example when a gNB connects that wasn't planned for: the location of a UE in one of its cells gives the id to register.

### Quality profiles and QoS sessions

Quality profiles are part of the deployment, like the PCF's `qos_profiles`: they come from configuration, and TEP only lists and gets them. Each is in the shape of the TEP quality profile body:

```yaml
tep:
  quality-profiles:
    - name: QOS_M
      five-qi: 2
      description: GBR flow with 5QI 2, suitable for video (live streaming)
      status: ACTIVE
      max-downstream-rate: { value: 40, unit: Mbps }
      max-upstream-rate: { value: 40, unit: Mbps }
      target-min-downstream-rate: { value: 10, unit: Mbps }
      priority: 40
      preemption-vulnerability: PREEMPTABLE
```

Each needs its `five-qi`, the 5QI the PCF's `qos_profiles` maps its name to. They are checked at startup against the TEP body's constraints, and an invalid profile, one without a `five-qi` or a repeated name stops the app from starting. A change takes a restart, as the PCF's does. `POST /quality-profile` and `PUT` and `DELETE /quality-profile/{name}` aren't in the spec and answer 405.

An AS session names a profile in its `qosReference`. TEP passes that name on to the Open5GS PCF as the media component's `qosReference`, as a 3GPP NEF does, and the PCF works out the QoS from it:

- **5QI.** The PCF looks the name up in its `qos_profiles` config, which maps each reference to a QoS index (5QI). Every profile used for sessions needs an entry there under its name, with its `fiveQi`. A name the PCF doesn't know is a 403 from the PCF ("Unknown qosReference"), which TEP answers as a 500 with the PCF's reason.
- **PCC rule.** The UE's PDU session must have a PCC rule for that 5QI in its subscription data, or the PCF answers 403 ("No PCC Rule in DB"). TEP adds it: before asking the PCF, it finds the PDU session with the subscription's `ueIpv4Addr` in the SMF's `pdu-info`, and gives that session a PCC rule for the profile's `fiveQi` if it has none. The PCF reads the rules on every request, so the rule counts at once. A session keeps the rules it got until it needs room for a new one.
  - A subscription without `ueIpv4Addr`, or for an address no PDU session has, is a 400 on `/ueIpv4Addr`, and nothing reaches the PCF.
  - Open5GS takes at most 8 PCC rules in a session (more makes the PCF abort when it loads the session). When a session has 8 and none for the 5QI, TEP first removes the rules no QoS session on that UE address uses, for example those of profiles since removed. It never removes a rule with flows of its own (a static rule from the WebUI, set up as a dedicated flow with the session) or the rule for the session's default 5QI. Only when every rule is still needed is the request a 409.
  - The default QoS flow is untouched: Open5GS builds it from the session's own `qos` (5QI and ARP) and AMBR, not from a PCC rule, and TEP never changes those.
  - An Open5GS PDU session has at most 4 QoS flows, the default one included, and every app session takes one of its own, so a UE has at most 3 QoS sessions at a time. TEP counts the flows in the SMF's `pdu-info` before it asks the PCF: a create, or a PATCH to a profile with another 5QI (which briefly takes a new app session), on a PDU session that has 4 is a 409. A PATCH that keeps the 5QI changes the flow in place and needs no room.
  - Rarely, a rule TEP just added for a create still in flight can be removed by a concurrent create on a session already full of rules; that create then gets the PCF's 403.
- **Bit rates and ARP** come from the profile's own fields, as below. A rate the profile doesn't give, the PCF takes from the PCC rule.

| Profile | Media component | What the PCF does with it |
|---|---|---|
| `name` | `qosReference` | Picks the 5QI from `qos_profiles` |
| `maxDownstreamRate`, `maxUpstreamRate` | `marBwDl`, `marBwUl` | MBR. TEP's rate units are the 3GPP ones, so `{"value": 5, "unit": "Mbps"}` is sent as `"5 Mbps"`. |
| `targetMinDownstreamRate`, `targetMinUpstreamRate` | `mirBwDl`, `mirBwUl` | GBR. Without them, the GBR is the MBR. |
| `priority` | `resPrio` | ARP priority level, through the PCF's `reservation_priorities` config. 1–100 is banded into `PRIO_16` (highest) to `PRIO_1`: 1–7 is `PRIO_16`, 8–13 `PRIO_15`, …, 95–100 `PRIO_1`. A `resPrio` missing from that config leaves the PCC rule's ARP priority as it is. |
| `preemptionCapability` | `preemptCap` | ARP pre-emption capability: `MAY_PREEMPT` or `NOT_PREEMPT` |
| `preemptionVulnerability` | `preemptVuln` | ARP pre-emption vulnerability: `PREEMPTABLE` or `NOT_PREEMPTABLE` |

The PCF adds the rates once per flow: a `permit out` flow adds the downstream rates, a `permit in` flow the upstream ones. So a subscription whose flows have two descriptions in one direction gets twice that rate. The other profile fields (durations, packet delay budget, jitter, packet error loss rate) have nothing in the media component that Open5GS applies; the 5QI behind the `qosReference` gives the packet delay budget and error rate.

A session's `qosReference` must name an `ACTIVE` profile; otherwise the create is a 400 with an `invalidParams` entry for `/qosReference`, and nothing reaches the PCF.

A session is changed with a PATCH (a JSON merge patch). TEP applies a new `qosReference`, `flowInfo` (which replaces the flows) and `notificationDestination`; any other field is a 400 naming it, and nothing reaches the PCF. A new `qosReference` must name an `ACTIVE` profile, and the UE's PDU session gets the PCC rule for its 5QI as on create. How the PCF is told depends on the 5QI:

- **Same 5QI** as the app session's (new flows, or a profile with other rates or ARP): TEP patches the app session. It always sends the whole media component, flows included, because the PCF takes a component without flows as one whose flows are gone.
- **Another 5QI**: the PCF would add a PCC rule for the new 5QI to the app session and keep the old one, so the old rule would stay on the same flows. TEP instead creates a new app session, moves the session to it, and then deletes the old one. The session keeps its id, URL and notifications. If the old app session can't be deleted, the session still runs on the new one, and the error is logged. When the PCF reports the end of an app session the session has moved off (its `resUri`), TEP ignores it.

Notes on the 3GPP API:
- The ip-addrs, ip-domain and mac-addrs filters of the list operation are ignored; it returns every session of the SCS/AS.
- When the PCF ends a session, the SCS/AS is notified at `{notificationDestination}/notifications`, the 3GPP spec's notification path. A failed notification is logged and the session is still removed.

### Correlation

Every request may carry an `x-correlator` header, as in CAMARA APIs; camara-tep's services send theirs on every TEP call. The response carries it back, errors included, and every log line of the request has it (the ECS `correlator` field, or `[…]` in plain text). Without one, the logs get a generated UUID and the response none. The TEP operations declare the header, so an invalid one (not up to 256 of `a-zA-Z0-9-_:;./<>{}`) is a 400. The 3GPP AsSessionWithQoS operations don't declare it, but get the same echo and logs.

### Errors

Every error is an RFC 9457 problem (`application/problem+json`) with `status`, `title`, `detail` and `instance`. A 400 caused by one field adds the 3GPP `invalidParams` (`[{"param": "/qosReference", "reason": "…"}]`). A failing PCF or metrics endpoint is a 500 whose detail names it.

## Configuration

| Variable | Default | |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/open5gs` | The Open5GS database |
| `PCF_BASE_URL` | `http://localhost:7777/npcf-policyauthorization/v1` | Npcf_PolicyAuthorization API root |
| `PCF_NOTIFICATION_URL` | `http://localhost:8080/notifications` | This app's `/notifications` as the PCF reaches it |
| `AMF_METRICS_URL` | `http://localhost:9090/ue-info` | AMF UE contexts |
| `SMF_METRICS_URL` | `http://localhost:9091/pdu-info` | SMF PDU sessions |
| `METRICS_CONNECT_TIMEOUT`, `METRICS_READ_TIMEOUT` | `2s`, `5s` | For both metrics endpoints |
| `SERVER_PORT`, `MANAGEMENT_PORT` | `8080`, `8081` | API, and actuator (health, info, prometheus) |
| `LOG_FORMAT` | `ecs` | Structured console logs; empty for plain text |
| `ACCESS_LOG_ENABLED` | `true` | One log line per API request (see [Logging](#logging)) |
| `SPRINGDOC_ENABLED` | `true` | Swagger UI and `/v3/api-docs` |
| `SHUTDOWN_TIMEOUT` | `20s` | Graceful shutdown |

The PCF talks cleartext HTTP/2 with prior knowledge both ways: to the PCF through OkHttp, and from the PCF through Tomcat (`server.http2.enabled`).

**Home PLMNs.** `tep.home-plmns` lists the home PLMNs, each as MCC + MNC with the MNC at its own length, quoted so YAML keeps the leading zeros:

```yaml
tep:
  home-plmns:
    - "00101"
    - "310410"   # a 3-digit MNC
```

A UE served by any other PLMN has `roaming: true` in its status. List the home PLMN and any others to treat as home. An entry that isn't 5 or 6 digits stops the app from starting. Without the list, statuses have no `roaming`. The `local` profile sets `00101`, Open5GS's sample PLMN.

Readiness includes Mongo: without the database no request can be answered, so the pod leaves the Service until Mongo is back.

## Logging

Logged the way camara-tep's services log. Logs are ECS JSON on stdout by default (`LOG_FORMAT=ecs`); `LOG_FORMAT=` (empty) or the `local` profile gives plain text.

**One line per API request**, once it's answered (`AccessLogFilter`), TEP, 3GPP and the PCF's notifications alike. Actuator, Swagger UI and the spec files are left out (`em.access-log.exclude`); `ACCESS_LOG_ENABLED=false` turns it off.

```
POST /tep-api/v1/subscriber/status 404 12ms
```

| Field | Content |
|---|---|
| `http.request.method`, `url.path` | The path only, never the query string; a phone number in it is masked |
| `http.route` | The matched route template, when a controller answered |
| `http.response.status_code` | The HTTP status |
| `error.type` | The exception behind a problem answer, if any |
| `event.duration` | Nanoseconds |

**One line per call this app makes** (logger `gr.pnet.em.OutboundCalls`): to the AMF and SMF metrics, the PCF and an SCS/AS, e.g. `PCF POST /npcf-policyauthorization/v1/app-sessions 201 18ms`, with `call.target`, `call.method`, `call.status_code` and `event.duration`. An SCS/AS is logged by its host only, since its notification URL is the client's. A call with no answer is a WARN. `LOGGING_LEVEL_GR_PNET_EM_OUTBOUNDCALLS=WARN` keeps only those. Every call carries the request's `x-correlator`.

**On every line of a request** (MDC): `correlator`, the request's `x-correlator` or a generated UUID (see [Correlation](#correlation)). camara-tep sends its own on every TEP call, so one value finds a request across the CAMARA API, TEP and the Open5GS calls.

| Level | When |
|---|---|
| INFO | Requests answered below 500, calls to Open5GS and SCS/ASs, QoS session and PCC rule lifecycle, the home PLMNs at startup |
| WARN | Requests answered 5xx (plus a line with the Open5GS cause), calls with no answer, failed notifications, no home PLMNs at startup |
| ERROR | Unexpected errors, with the stack trace (a bug), and a QoS session that left a PCF app session behind |

**Never logged:** request or response bodies, query strings, subscriber credentials, IMSIs (a subscriber is logged by its TEP id), SCS/AS notification URLs (only their host), and exception messages from a request, except the masked Open5GS cause of a 5xx. Phone numbers that do appear are masked, e.g. `+3069******78`.

## Running locally

Requirements: JDK 17+ (Maven comes through the bundled wrapper) and a MongoDB.

```bash
# Against a single-host Open5GS (Mongo on 127.0.0.1, PCF on 127.0.0.13, AMF/SMF metrics on 127.0.0.5/127.0.0.4)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Swagger UI is at http://localhost:8080/. The `local` profile configures nine sample quality profiles, `QOS_1` to `QOS_8` and `QOS_69`, each named after its 5QI ([application-local.yaml](src/main/resources/application-local.yaml)). To request a session with one:

```bash
curl -i -X POST -H 'Content-Type: application/json' --data @dev/requests/create-qos-session.json \
  http://localhost:8080/tep-api/v1/3gpp-as-session-with-qos/v1/scs-as-1/subscriptions
```

The PCF needs a `qos_profiles` entry for each sample profile, with its `fiveQi`, in its `pcf.yaml`, and restarting:

```yaml
pcf:
  qos_profiles:
    - reference: QOS_1
      qos_index: 1
    - reference: QOS_2
      qos_index: 2
    - reference: QOS_3
      qos_index: 3
    - reference: QOS_4
      qos_index: 4
    - reference: QOS_5
      qos_index: 5
    - reference: QOS_6
      qos_index: 6
    - reference: QOS_7
      qos_index: 7
    - reference: QOS_8
      qos_index: 8
    - reference: QOS_69
      qos_index: 69
```

A PDU session holds at most 8 PCC rules, but TEP removes the ones no QoS session uses when it needs room, so a UE can use all nine profiles, at most 3 at a time (Open5GS's 4 QoS flows, the default one included).

## Building and testing

```bash
./mvnw verify    # unit, web and integration tests, and the 80% line-coverage gate
```

The integration tests (`*IT`) run the whole app against a real MongoDB, with the PCF and the AMF/SMF metrics stubbed. Mongo is `TEST_MONGODB_URI` if set (CI: a `mongo:7` service container), otherwise Testcontainers starts one, which needs Docker. Generated code is excluded from coverage.

## Deploying

**Image.** Jib builds it, with no Dockerfile. It is based on `eclipse-temurin:17-jre`, runs as uid 10001 and exposes ports 8080 and 8081:

```bash
./mvnw -DskipTests package jib:dockerBuild    # open5gs-em:local in the local Docker
```

**Helm.** [charts/open5gs-em](charts/open5gs-em) is a hardened chart: non-root, read-only root filesystem, probes on the management port, and an optional Ingress, HPA, PDB and ServiceMonitor. Set:
- `config.pcfBaseUrl`, `config.amfMetricsUrl` and `config.smfMetricsUrl`
- `config.pcfNotificationUrl`, when the PCF is outside the cluster (by default it is this release's Service)
- the Mongo URI, from an existing Secret (`mongodb.existingSecret`, key `MONGODB_URI`) or from `mongodb.uri`
- `qualityProfiles`, the quality profiles, in the shape of the TEP body (keys as in the API, e.g. `maxDownstreamRate`, and each with its `fiveQi`). The chart renders them into their own ConfigMap, mounted at `/config` and imported with `SPRING_CONFIG_IMPORT`. A change rolls the pods.
- `homePlmns`, the home PLMNs, a list such as `["00101"]`, rendered into the same ConfigMap as `tep.home-plmns`.

**Open5GS and EM together.** The open5gs-em-stack chart, in the [open5gs-em-stack](https://github.com/pnet-software/open5gs-em-stack) repository, deploys the Open5GS 5G core (no UPF, no 4G), this chart and open5gs-swap-events (which keeps `swap_events` in sync) as one release. It pulls this chart from Docker Hub at a pinned version. EM's URLs and Mongo URI point at that release, and the PCF's `qos_profiles` is rendered from the same `global.qualityProfiles` EM serves, so a profile's name and 5QI can't differ between the two.

The Ingress routes only `/tep-api`. `/notifications` stays cluster-internal.

## Releases

A tag `vX.Y.Z` releases, through GitHub Actions ([ci.yml](.github/workflows/ci.yml)):

| Tag `v1.0.0` produces | |
|---|---|
| Maven version | `1.0.0` (`-Drevision`) |
| Image | `docker.io/pnetrepo/open5gs-em:v1.0.0` (also short SHA, `latest`) |
| Chart | `oci://registry-1.docker.io/pnetrepo/open5gs-em-chart`, version `1.0.0`, appVersion `v1.0.0` |
| GitHub Release | `v1.0.0`, with notes generated from the commits |

After a release, the [open5gs-em-stack](https://github.com/pnet-software/open5gs-em-stack) repository takes it by changing the open5gs-em-chart version in its Chart.yaml.

Pull requests and pushes to `main` run `test` and `helm-lint`. Publishing uses the organization secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`, and the variable `DOCKERHUB_NAMESPACE` (`pnetrepo`).

See [CONTRIBUTING.md](CONTRIBUTING.md) for conventions, adding an operation and syncing the TEP spec.

## License

Copyright 2026 P-NET New Generation Emerging Networks & Verticals Private Company.

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify and redistribute this work, provided you keep the copyright and license notices and include the [NOTICE](NOTICE) file in what you distribute. The 3GPP API specifications in this repository keep their own copyright; see [NOTICE](NOTICE).
