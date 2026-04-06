# Service Mesh Migration Guide
**Cloud-Core Mesh → Istio + GatewayAPI HTTPRoute**

---

## Overview

This guide walks you through migrating your service from the Cloud-Core mesh to Istio-based routing using Kubernetes GatewayAPI HTTPRoute custom resources. Follow the steps in order. Each step builds on the previous one.

---

## Step 1 — Migrate Existing Mesh CRs to HTTPRoute CRs

Use the Cursor skill "Migrate Cloud-Core mesh CR to HTTPRoute CR" to automatically convert your existing mesh custom resources (`FacadeService`, `Gateway`, `RouteConfiguration`) into GatewayAPI HTTPRoute manifests while keeping the chart deployable on both mesh types.

### How to run

1. Open Cursor IDE in your repository root.
2. Run the slash command: `/migrate-cr-to-httproute <path-to-your-helm-chart>`
3. The skill will:
    - Wrap all original `FacadeService`, `Gateway`, and `RouteConfiguration` CRs in `{{- if eq .Values.SERVICE_MESH_TYPE "Core" }}` guards
    - Generate new `-istio.yaml` files alongside each original (e.g. `gateway.yaml` → `gateway-istio.yaml`) wrapped in `{{- if eq .Values.SERVICE_MESH_TYPE "Istio" }}`
    - Convert `Gateway` (ingress/egress type) → Istio `Gateway` + `HTTPRoute`
    - Convert `RouteConfiguration` → `HTTPRoute` with correct `parentRefs` (Gateway or Service depending on gateway type)
    - Omit `FacadeService` and `Gateway` (mesh type) from Istio output — their routes become east-west `HTTPRoute` resources with Service `parentRefs`
    - Add `SERVICE_MESH_TYPE: Core` to `values.yaml` and update `values.schema.json`
4. Review the generated `-istio.yaml` files and the modified originals.
5. Commit all modified and generated files to your branch.

---

## Step 1.1 — Manually Handle Flagged Features

After the skill runs, it prints a summary of items it could not migrate automatically, marked with `# ⚠ MANUAL REVIEW REQUIRED`. You must address these before proceeding.

### Flagged items

| Item                                                    | Why it's flagged                                    | What to do                                                                   |
|---------------------------------------------------------|-----------------------------------------------------|------------------------------------------------------------------------------|
| `directResponse` routes                                 | No HTTPRoute equivalent                             | Implement via a dedicated backend or remove                                  |
| `retries` configuration                                 | Not supported in HTTPRoute v1                       | Use an Istio `DestinationRule` or remove                                     |
| Regex path matches                                      | HTTPRoute only supports `Exact`, `PathPrefix`       | Rewrite as prefix match or split into multiple routes                        |
| Named helpers (`{{- include ... }}`) producing mesh CRs | Skill cannot rewrite helper output                  | Manually add CORE/ISTIO guards inside the helper                             |
| Unresolved gateway references                           | Gateway type could not be determined from the chart | Re-run after confirming gateway type (ingress vs mesh) with the skill prompt |

> **✅ Done when:** All `⚠ MANUAL REVIEW REQUIRED` comments are resolved and a Helm dry-run with `SERVICE_MESH_TYPE=Istio` renders valid HTTPRoute resources with no errors.
```bash
helm template . --set SERVICE_MESH_TYPE=Istio | grep -E 'kind: (HTTPRoute|Gateway)'
```

---

## Step 2 — Generate HTTPRoute CRs from Route Registration Code

Use the Cursor skill "Generate HTTPRoute CRs from Go or Java route registration code" to produce HTTPRoute manifests directly from your application source code. This ensures your code-defined routes are reflected in the cluster configuration.

### How to run

1. Open Cursor IDE.
2. Run the slash command: `/httproute-from-code <path>` pointing at your routes directory or file.
3. The skill scans Go and Java files, detects route definitions, and outputs one HTTPRoute CR per RouteType.
4. Generated files are saved to `k8s/routes/<microservice-name>-httproutes.yaml`.
5. Review the summary table printed by the skill. Resolve any errors before continuing.
6. Commit all modified and generated files to your branch.

> **ℹ️ Tip:** You can run the skill against a single file or an entire directory.

---

## Step 3 — Add the Maven Plugin (Java services only)

Java services must add the route-registration [Maven plugin](https://github.com/Netcracker/qubership-core-control-plane/blob/main/httproute-generator/README.md) . This plugin validates route definitions at build time and prevents invalid configurations from reaching the cluster.

### Add to pom.xml

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.qubership.cloud.core</groupId>
            <artifactId>httproutes-generator-maven-plugin</artifactId>
            <version>0.0.1-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals>
                        <goal>generate-routes</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <packages>
                    <package>com.example</package>
                </packages>
                <servicePort>8080</servicePort>
                <outputFile>gateway-httproutes.yaml</outputFile>
                <backendRefVal>{{ .Values.DEPLOYMENT_RESOURCE_NAME }}</backendRefVal>
            </configuration>
        </plugin>
    </plugins>
</build>
```

After adding the plugin, run a local build to confirm it passes:

```bash
mvn clean compile
```

> **⚠️ Note:** Go services do not require this step. Skip to Step 4.

---

## Step 4 — Set the SERVICE_MESH_TYPE Environment Variable

All services (Java and Go) must expose the environment variable that controls which mesh implementation is active. Set it to enable Istio mode:

```
SERVICE_MESH_TYPE=Istio
```

### Where to set it

**Helm values file** (`values.yaml` or environment-specific override):

```yaml
env:
  SERVICE_MESH_TYPE: Istio
```

**Kubernetes Deployment manifest** (if not using Helm):

```yaml
env:
  - name: SERVICE_MESH_TYPE
    value: Istio
```

> **⚠️ Important:** Without this variable set to `"Istio"`, the conditional blocks in your Helm templates will not render the HTTPRoute CRs and routes will not be registered.

---

## Step 5 — Verify All Routes Are Wrapped in Istio Conditionals

Every HTTPRoute CR in your Helm templates must be wrapped in a conditional guard so it only renders when Istio is the active mesh. This prevents HTTPRoute resources from being applied to clusters running a different mesh implementation.

### Required pattern

```yaml
{{- if eq .Values.SERVICE_MESH_TYPE "Istio" }}
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: my-service-public-routes
  namespace: {{ .Values.NAMESPACE }}
spec:
  ...
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: my-service-private-routes
  ...
{{- end }}
```

### Checklist

- One `{{- if eq .Values.SERVICE_MESH_TYPE "Istio" }}` wraps ALL HTTPRoute CRs together.
- The closing `{{- end }}` appears after the last CR.
- No HTTPRoute CR exists outside a conditional block.

To verify, do a Helm dry-run with Istio disabled and confirm no HTTPRoute objects are rendered:

```bash
helm template . --set SERVICE_MESH_TYPE=other | grep 'kind: HTTPRoute'
# Expected: no output
```

---

## Step 6 — Switch to the New Mesh-Type-Aware Libraries

Replace the old route-posting libraries with the new mesh-aware versions. The new libraries read the `SERVICE_MESH_TYPE` flag at runtime and skip posting routes to the Cloud-Core Control-Plane when Istio is active — Istio handles routing directly from the HTTPRoute CRs instead.

### Java — update your dependency

#### Spring

```xml
<dependency>
    <groupId>com.netcracker.cloud</groupId>
    <artifactId>route-registration-webclient</artifactId>
    <version>TODO</version>
</dependency>

<dependency>
    <groupId>com.netcracker.cloud</groupId>
    <artifactId>route-registration-restclient</artifactId>
    <version>TODO</version>
</dependency>
```

#### Quarkus

```xml
<dependency>
    <groupId>com.netcracker.cloud.quarkus</groupId>
    <artifactId>routes-registrator</artifactId>
    <version>TODO</version>
</dependency>
```

### Go — update your go.mod

```text
"github.com/netcracker/qubership-core-lib-go-rest-utils/v2/route-registration" version must be >= v2.4.5 //TODO
```

### What changes at runtime

| Condition                 | Old library                   | New library                                 |
|---------------------------|-------------------------------|---------------------------------------------|
| `SERVICE_MESH_TYPE=Istio` | Posts routes to control plane | Skips control plane — Istio handles routing |
| Any other value           | Posts routes to control plane | Posts routes to control plane (unchanged)   |

---

## Final Checklist

Before raising a PR, verify all of the following:

- [ ] Cursor skill run: existing mesh CRs converted to HTTPRoute CRs
- [ ] Flagged features from Step 1.1 manually resolved
- [ ] Cursor skill run: route registration code converted to HTTPRoute CRs
- [ ] Maven plugin added and local build passes (Java only)
- [ ] `SERVICE_MESH_TYPE=Istio` set in Helm values / Deployment
- [ ] All HTTPRoute CRs wrapped in a single `{{- if eq .Values.SERVICE_MESH_TYPE "Istio" }}` block
- [ ] New mesh-aware libraries replace old route-posting libraries
