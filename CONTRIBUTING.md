# Contributing

See the [README](README.md) for what the app does, how to build and test it, and how releases work.

## Layout

```
src/main/resources/static/openapi/   The TEP spec (tep-api.openapi.yaml) and the 3GPP files; the server is generated
                                     from here, and Swagger UI serves the same files
src/main/java/gr/pnet/em/
  tep/<resource>/                    One TEP resource: its delegate (implements the generated *ApiDelegate), service and
                                     Mongo document or Open5GS mapping
  nef/                               What a 3GPP NEF/SCEF does: the 3GPP APIs exposed to an SCS/AS, carried out on the PCF
    qossession/                      AS sessions with QoS: the two 3GPP delegates and the session store
    pcf/                             The Npcf_PolicyAuthorization client (generated, OkHttp) and the app session mapping
    notification/                    The PCF's callback, and the notifications to the SCS/AS
  error/                             RFC 9457 problem details for every error
  support/                           Paging, the natural-id Mongo store, Location headers
charts/open5gs-em/                   Helm chart
dev/requests/                        Sample requests
```

Generated code lives in `gr.pnet.em.generated.*` (`tep` server, `pcfclient`, `scsasclient`) under `target/generated-sources`, and is excluded from coverage.

## Conventions

- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `build:`, `ci:`, `test:`, `docs:`, `chore:`). Scope by area, e.g. `feat(tep): …`, `fix(qos): …`, `build(chart): …`.
- **Branching:** trunk-based on `main`, with short-lived feature branches and pull requests.
- **Versioning:** SemVer. Releases come only from `vX.Y.Z` tags, and each gets a GitHub Release with generated notes; between releases, `main` builds `1.0.0-SNAPSHOT`.
- **Errors:** throw the exceptions in `gr.pnet.em.error`; never build error responses by hand. Pass the JSON pointer of the offending field to `InvalidRequestException` when there is one.
- **Open5GS data:** a collection Open5GS owns (`subscribers`) is read and written as raw `org.bson.Document`, and only TEP's fields are set, so nothing the WebUI or Open5GS stores is lost.

## Adding a TEP operation

1. Add it to [tep-api.openapi.yaml](src/main/resources/static/openapi/tep-api.openapi.yaml), and bump `info.version`.
2. `./mvnw generate-sources`, then implement the new method of the generated `*ApiDelegate` in the resource's delegate. A delegate method the app doesn't implement answers 501.
3. Add web tests (`gr.pnet.em.web`) for its statuses, and an integration test if it touches Mongo or Open5GS.
4. Sync the spec (below).

## Syncing the TEP spec

The contract is [tep-openapi](https://github.com/pnet-software/tep-openapi): `tep-api.openapi.yaml` and `TS29122_AsSessionWithQoS_Bundle.openapi.yaml`, the self-contained 3GPP spec it references. This repo and camara-tep keep identical copies of both, under the same names, so a change to the spec is copied to all three:

1. **`tep-openapi`**: the two files at the repository root.
2. **This repo**: `src/main/resources/static/openapi/`.
3. **camara-tep `libs/tep-client`**: `src/main/openapi/`. If the change adds 3GPP API classes a service needs, wire them as beans in `TepClientConfig`, as `SubscriberManagementApi` and `QualityProfileApi` are.

The two files must sit side by side: the spec references the bundle by a relative path, and the bundle references itself by its own name.

Keep the spec at OpenAPI 3.0.x while it references the 3GPP files: in a 3.1 document the generators inline external references, and the 3GPP models get names like `FetchAllASSessionWithQoSSubscriptions200ResponseInner`.
