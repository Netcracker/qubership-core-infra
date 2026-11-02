package gate

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/go-cmp/cmp/cmpopts"
)

var today = time.Date(2026, 10, 29, 0, 0, 0, 0, time.UTC)

func testSchemas(t *testing.T) *Schemas {
	t.Helper()
	s, err := LoadSchemas(os.DirFS("../../schemas"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func infraDefaults(t *testing.T) []byte {
	t.Helper()
	data, err := os.ReadFile("../../defaults.json")
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// load fills the schemas, today and, unless set, the infra defaults.
func load(t *testing.T, o LoadOptions) *Policy {
	t.Helper()
	o.Schemas, o.Today = testSchemas(t), today
	if o.Defaults == nil {
		o.Defaults, o.DefaultsName = infraDefaults(t), "infra:defaults.json"
	}
	p, err := Load(o)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

// policy loads the infra defaults and, unless empty, repoConfig as the repository file.
func policy(t *testing.T, repoConfig string) *Policy {
	t.Helper()
	dir := t.TempDir()
	if repoConfig != "" {
		writeFile(t, filepath.Join(dir, RepoConfigFile), repoConfig)
	}
	return load(t, LoadOptions{RepoDir: dir})
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func fixtures(t *testing.T) Input {
	t.Helper()
	in := Input{Branch: "main"}
	if err := in.ReadFindings(testSchemas(t), "testdata/findings"); err != nil || len(in.Problems) > 0 {
		t.Fatal(err, in.Problems)
	}
	return in
}

func repoFixture(t *testing.T) string {
	t.Helper()
	data, err := os.ReadFile("testdata/repo/" + RepoConfigFile)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

// headFile builds a findings file of one adapter.
func headFile(name, eco string, status AdapterStatus, checks []string, items ...Item) *FindingsFile {
	f := &FindingsFile{Schema: FindingsSchema, Revision: Head, Findings: items}
	f.Adapter.Adapter = Adapter{Name: name, Ecosystem: eco, Status: status}
	f.Adapter.Checks = checks
	return f
}

// want is the effective level and status of the only finding of check whose package contains pkg; without a
// status there must be no such finding.
type want struct {
	check, pkg string
	level      Level
	status     Status
}

func (w want) String() string {
	if w.status == "" {
		return "no " + w.check + " " + w.pkg
	}
	return w.check + " " + w.pkg
}

func matching(r *Report, check, pkg string) []*Finding {
	var found []*Finding
	for _, f := range r.Findings {
		if f.Check == check && strings.Contains(f.Package, pkg) {
			found = append(found, f)
		}
	}
	return found
}

func find(t *testing.T, r *Report, check, pkg string) *Finding {
	t.Helper()
	found := matching(r, check, pkg)
	if len(found) != 1 {
		t.Fatalf("want one %s finding for %q, got %d in %v", check, pkg, len(found), keys(r))
	}
	return found[0]
}

// expectFindings checks each wanted finding in a subtest of its own.
func expectFindings(t *testing.T, r *Report, wants ...want) {
	t.Helper()
	for _, w := range wants {
		t.Run(w.String(), func(t *testing.T) {
			if w.status == "" {
				if found := matching(r, w.check, w.pkg); len(found) > 0 {
					t.Fatalf("unexpected %s", found[0].Key)
				}
				return
			}
			if f := find(t, r, w.check, w.pkg); f.Level != w.level || f.Status != w.status {
				t.Errorf("%s: got %s/%s, want %s/%s", f.Key, f.Level, f.Status, w.level, w.status)
			}
		})
	}
}

func keys(r *Report) []string {
	var out []string
	for _, f := range r.Findings {
		out = append(out, f.Key+" "+f.Level.String()+" "+string(f.Status))
	}
	return out
}

// problems maps the configuration pointer of each problem of the policy to its check.
func problems(p *Policy) map[string]string {
	out := map[string]string{}
	for _, f := range p.Problems {
		_, pointer, _ := strings.Cut(f.Package, "#")
		out["#"+pointer] = f.Check
	}
	return out
}

func TestDefaultsAreValid(t *testing.T) {
	p := policy(t, "")
	if len(p.Problems) > 0 {
		t.Fatal(p.Problems)
	}
	if p.Config.Mode != ModeReport || p.Config.MaxExceptionDays != 180 {
		t.Errorf("unexpected defaults: %+v", p.Config)
	}
	if !slices.Contains(p.Config.Locked, "pin-exceeded") {
		t.Error("pin-exceeded must be locked")
	}
}

func TestFixturePolicy(t *testing.T) {
	r := policy(t, repoFixture(t)).Evaluate(fixtures(t))
	expectFindings(t, r,
		// an exception wins over the baseline
		want{"pin-exceeded", "amqp-client", Fail, Allowed},
		// the matching exception expired: the finding counts again
		want{"pin-exceeded", "jackson-bom", Fail, New},
		want{ExceptionExpired, "/exceptions/2", Fail, New},
		// matches nothing although its checks ran
		want{ExceptionUnused, "/exceptions/1", Warn, New},
		// no reason and until; no until
		want{ExceptionInvalid, "/exceptions/3", Fail, New},
		want{ExceptionInvalid, "/exceptions/4", Fail, New},
		want{"pin-lagging", "slf4j-api", Warn, New},
		// an ignore rule of the check
		want{"apidiff-internal", "openshift", Warn, Allowed},
		// symbol regex; expires within two weeks
		want{"apidiff-exposed", "opensearch-go", Fail, Allowed},
		want{ExceptionExpiring, "/exceptions/5", Warn, New},
		// vuln-new did not run in this change: its exception is not unused
		want{check: ExceptionUnused, pkg: "/exceptions/6"},
		want{"pin-conflict", "opentelemetry", Warn, New},
		want{"feature-snapshot", "consul", Fail, New},
	)

	amqp := find(t, r, "pin-exceeded", "amqp-client").Exception
	if diff := cmp.Diff(&ExceptionRef{Source: RepoConfigFile + "#/exceptions/0", Until: "2027-01-31"}, amqp,
		cmpopts.IgnoreFields(ExceptionRef{}, "Reason", "Issue")); diff != "" || !strings.Contains(amqp.Reason, "CVE-2026-0001") {
		t.Errorf("exception of amqp-client %q (-want +got):\n%s", amqp.Reason, diff)
	}
	if got := find(t, r, "apidiff-internal", "openshift").Exception; got.Source != RepoConfigFile+"#/checks/apidiff-internal/ignore/0" || got.Until != "" {
		t.Errorf("ignore rule: %+v", got)
	}
	if got := find(t, r, ExceptionUnused, "/exceptions/1").Locations; got[0].File != RepoConfigFile {
		t.Errorf("unused exception location: %v", got)
	}
	if msg := find(t, r, ExceptionInvalid, "/exceptions/3").Message; !strings.Contains(msg, "missing properties 'reason', 'until'") {
		t.Errorf("message %q", msg)
	}
	if r.Verdict != VerdictFail || r.Mode != ModeReport || r.Failed() {
		t.Errorf("verdict %s in %s mode, failed %v", r.Verdict, r.Mode, r.Failed())
	}
	if r.Counts.Allowed != 3 || r.Counts.Preexisting != 0 {
		t.Errorf("counts %+v", r.Counts)
	}
	if diff := cmp.Diff([]string{"infra:defaults.json", RepoConfigFile}, r.Config); diff != "" {
		t.Errorf("config sources (-want +got):\n%s", diff)
	}
	// the repository narrows what the adapters check, locked checks included: the report shows it
	if diff := cmp.Diff(map[string]Scope{
		Maven: {Exclude: []string{"^core-maven-plugins/.*/src/test/resources/"}, Transitive: Warn},
		Go:    {Modules: []string{"."}},
	}, r.Scope); diff != "" {
		t.Errorf("scope (-want +got):\n%s", diff)
	}
	if r.Findings[0].Level != Fail || r.Findings[len(r.Findings)-1].Status != Allowed || !slices.IsSortedFunc(r.Findings, compareFindings) {
		t.Errorf("findings are not ordered by importance: %v", keys(r))
	}
}

func TestBaseline(t *testing.T) {
	r := policy(t, "").Evaluate(fixtures(t))
	expectFindings(t, r,
		// on the base branch with the same key
		want{"pin-exceeded", "amqp-client", Warn, Preexisting},
		want{"pin-exceeded", "jackson-bom", Fail, New},
		// the version is part of the key: a worse violation of an old problem is new
		want{"pin-exceeded", "lombok", Fail, New},
		// only the resolved dependency tree shows it: at most a warning by the infra defaults
		want{"pin-exceeded", "jackson-databind", Warn, New},
	)
	if r.Counts.Preexisting != 1 {
		t.Errorf("counts %+v", r.Counts)
	}
	if got := find(t, r, "pin-exceeded", "amqp-client").Key; got != "maven|pin-exceeded|com.rabbitmq:amqp-client|5.37.0|" {
		t.Errorf("key %q", got)
	}
}

func TestProtectedOnlyChecks(t *testing.T) {
	for _, tc := range []struct {
		name, branch string
		want         want
	}{
		{"feature branch", "feature/consul-auth", want{check: "feature-snapshot", pkg: "consul"}},
		{"release branch", "release/2026.4", want{"feature-snapshot", "consul", Fail, New}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			in := fixtures(t)
			in.Branch = tc.branch
			expectFindings(t, policy(t, "").Evaluate(in), tc.want)
		})
	}
}

func TestModes(t *testing.T) {
	found := Input{Head: []*FindingsFile{headFile("maven-pins", Maven, AdapterOK, []string{"pin-exceeded"},
		Item{Check: "pin-exceeded", Package: "g:a", Version: "2", Message: "above the pin 1"})}}
	// report mode stays green with a fail verdict — covered by TestFixturePolicy
	if r := policy(t, `{"version": 1, "mode": "enforce"}`).Evaluate(found); r.Verdict != VerdictFail || !r.Failed() {
		t.Errorf("enforce: verdict %s, failed %v", r.Verdict, r.Failed())
	}
	if r := policy(t, `{"version": 1, "mode": "enforce"}`).Evaluate(Input{}); r.Verdict != VerdictPass || r.Failed() {
		t.Errorf("nothing found: verdict %s, failed %v", r.Verdict, r.Failed())
	}
}

func TestLabelLevels(t *testing.T) {
	p := policy(t, `{"version": 1, "labels": {"levels": {"security": {"vuln-new": "fail"}}}}`)
	for _, tc := range []struct {
		labels []string
		level  Level
	}{
		{nil, Warn},
		{[]string{"automerge", "security"}, Fail},
	} {
		t.Run(fmt.Sprint(tc.labels), func(t *testing.T) {
			in := Input{Labels: tc.labels, Head: []*FindingsFile{headFile("go-library", Go, AdapterOK, []string{"vuln-new"},
				Item{Check: "vuln-new", Package: "golang.org/x/net", Version: "v0.40.0", Vuln: "GO-2026-0001", Message: "reachable"})}}
			expectFindings(t, p.Evaluate(in), want{"vuln-new", "x/net", tc.level, New})
		})
	}
}

func TestEcosystemLevels(t *testing.T) {
	item := Item{Check: "downgrade", Package: "p", Version: "1", Message: "down"}
	both := Input{Head: []*FindingsFile{headFile("maven-pins", Maven, AdapterOK, nil, item), headFile("go-library", Go, AdapterOK, nil, item)}}
	r := policy(t, `{"version": 1, "checks": {"downgrade": "fail"}, "ecosystems": {"go": {"checks": {"downgrade": "info"}}}}`).Evaluate(both)
	if diff := cmp.Diff([]string{"maven|downgrade|p|1| fail new", "go|downgrade|p|1| info new"}, keys(r)); diff != "" {
		t.Errorf("levels (-want +got):\n%s", diff)
	}
	// a check that is off is not reported at all
	expectFindings(t, policy(t, `{"version": 1, "checks": {"downgrade": "off"}}`).Evaluate(both), want{check: "downgrade"})
}

// A raised go directive forces consumers onto a newer Go and fails; go-dep-check reports a lowered one as a
// downgrade: it breaks no consumer.
func TestGoDirective(t *testing.T) {
	r := policy(t, "").Evaluate(Input{Head: []*FindingsFile{headFile("go-library", Go, AdapterOK, []string{"go-directive", "downgrade"},
		Item{Check: "go-directive", Package: "example.com/raised", Version: "1.23", From: "1.22", Message: "go directive raised"},
		Item{Check: "downgrade", Package: "example.com/lowered", Version: "1.21", From: "1.22", Message: "go directive lowered"},
	)}})
	expectFindings(t, r,
		want{"go-directive", "example.com/raised", Fail, New},
		want{"downgrade", "example.com/lowered", Warn, New},
	)
}

func TestToolErrors(t *testing.T) {
	in := Input{
		Head: []*FindingsFile{
			headFile("osv", Maven, AdapterError, nil),
			headFile("go-library", Go, AdapterOK, []string{"build"}, Item{Check: "pin-exceeded", Message: "wrong ecosystem"}),
			headFile("go-consumers", Go, AdapterSkipped, nil),
		},
		Expected: map[string]string{"maven-pins": "failure", "detect": "success", "go-consumers": "success", "go-other": "skipped"},
	}
	for _, tc := range []struct {
		name, config string
		// level of network-bound adapters
		soft Level
	}{
		{"infra defaults", "", Warn},
		{"raised by the repository", `{"version": 1, "checks": {"tool-error": "fail"}}`, Fail},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := policy(t, tc.config).Evaluate(in)
			expectFindings(t, r,
				// fail closed: a failed adapter, an adapter that wrote nothing, a broken contract
				want{ToolError, "maven-pins", Fail, New},
				want{ToolError, "detect", Fail, New},
				want{ToolError, "go-library", Fail, New},
				want{ToolError, "osv", tc.soft, New},
				want{check: ToolError, pkg: "go-consumers"},
				want{check: ToolError, pkg: "go-other"},
			)
			i := slices.IndexFunc(r.Adapters, func(a Adapter) bool { return a.Name == "go-other" })
			if i < 0 || r.Adapters[i].Status != AdapterSkipped {
				t.Errorf("adapters %+v", r.Adapters)
			}
		})
	}
}

func TestBrokenFindingsFile(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "maven-pins-head.json"),
		`{"schema": "dependency-gate-findings/v1", "adapter": {"name": "maven-pins", "ecosystem": "maven", "status": "ok"}, "revision": "head", "findings": []}`)
	writeFile(t, filepath.Join(dir, "sub", "broken.json"), `{"findings": [`)
	var in Input
	if err := in.ReadFindings(testSchemas(t), dir); err != nil {
		t.Fatal(err)
	}
	r := policy(t, "").Evaluate(in)
	expectFindings(t, r, want{ToolError, "maven-pins", Fail, New}, want{ToolError, "broken", Fail, New})
	if msg := find(t, r, ToolError, "maven-pins").Message; !strings.Contains(msg, "checks") {
		t.Errorf("message should name the missing field: %s", msg)
	}
}

func TestInvalidRepoConfig(t *testing.T) {
	for name, cfg := range map[string]string{
		"not json":           `{"version": 1,`,
		"schema":             `{"version": 1, "mode": "strict"}`,
		"unknown check":      `{"version": 1, "checks": {"pin-exceded": "warn"}}`,
		"wrong ecosystem":    `{"version": 1, "ecosystems": {"go": {"checks": {"pin-exceeded": "warn"}}}}`,
		"gate check off":     `{"version": 1, "checks": {"exception-unused": "off"}}`,
		"gate check lowered": `{"version": 1, "checks": {"tool-error": "info"}}`,
		"gate check warned":  `{"version": 1, "checks": {"config-invalid": "warn"}}`,
		"locked weakened":    `{"version": 1, "checks": {"pin-exceeded": "warn"}}`,
		"locked ignore":      `{"version": 1, "checks": {"pin-mismatch": {"ignore": [{"package": "g:a", "reason": "r"}]}}}`,
		"locked by eco":      `{"version": 1, "ecosystems": {"maven": {"checks": {"pin-exceeded": "warn"}}}}`,
		"locked by label":    `{"version": 1, "labels": {"levels": {"security": {"pin-exceeded": "warn"}}}}`,
		"infra only":         `{"version": 1, "maxExceptionDays": 365}`,
		"pins":               `{"version": 1, "ecosystems": {"maven": {"pins": "pins.json"}}}`,
		"bad regex":          `{"version": 1, "ecosystems": {"go": {"exclude": ["("]}}}`,
		"root with comma":    `{"version": 1, "ecosystems": {"maven": {"roots": ["a,b"]}}}`,
		"root outside":       `{"version": 1, "ecosystems": {"maven": {"roots": ["core/../.."]}}}`,
		"no roots":           `{"version": 1, "ecosystems": {"maven": {"roots": []}}}`,
		"module outside":     `{"version": 1, "ecosystems": {"go": {"modules": [".."]}}}`,
		"version 2":          `{"version": 2}`,
	} {
		t.Run(name, func(t *testing.T) {
			p := policy(t, cfg)
			if len(p.Problems) != 1 || p.Problems[0].Check != ConfigInvalid {
				t.Fatalf("problems %+v", p.Problems)
			}
			if !slices.Equal(p.Sources, []string{"infra:defaults.json"}) {
				t.Errorf("the repository file must be ignored: %v", p.Sources)
			}
			expectFindings(t, p.Evaluate(Input{}), want{ConfigInvalid, RepoConfigFile, Fail, New})
		})
	}
}

func TestExceptionDates(t *testing.T) {
	p := policy(t, `{"version": 1, "exceptions": [
		{"checks": ["vuln-*"], "vuln": "GHSA-1", "reason": "r", "until": "2027-06-01"},
		{"checks": ["vuln-new"], "vuln": "GHSA-2", "reason": "r", "until": "2026-10-29"},
		{"checks": ["vuln-new"], "vuln": "GHSA-3", "reason": "r", "until": "2026-10-28"},
		{"checks": ["config-invalid"], "path": "x", "reason": "r", "until": "2026-12-01"},
		{"checks": ["license-*"], "license": "/^BSL/i", "reason": "r", "until": "2026-12-01"}
	]}`)
	if diff := cmp.Diff(map[string]string{
		"#/exceptions/0": ExceptionInvalid,  // more than maxExceptionDays ahead
		"#/exceptions/1": ExceptionExpiring, // the last day still applies
		"#/exceptions/2": ExceptionExpired,
		"#/exceptions/3": ExceptionInvalid, // gate checks have no exceptions
	}, problems(p)); diff != "" {
		t.Errorf("problems (-want +got):\n%s", diff)
	}
	r := p.Evaluate(Input{Head: []*FindingsFile{headFile("osv", Maven, AdapterOK, []string{"license-changed"},
		Item{Check: "license-changed", Package: "g:a", License: "bsl-1.1", Message: "relicensed"})}})
	expectFindings(t, r, want{"license-changed", "g:a", Warn, Allowed})
}

func TestMerge(t *testing.T) {
	infra := `{"version": 1, "mode": "report", "protectedBranches": "^main$", "maxExceptionDays": 30,
		"labels": {"levels": {"security": {"vuln-new": "fail"}}},
		"ecosystems": {"maven": {"pins": "infra.json", "checks": {"downgrade": {"level": "warn", "ignore": [{"package": "a", "reason": "infra"}]}}}},
		"checks": {"license-not-allowed": {"level": "warn", "allow": ["MIT"]}},
		"families": [{"name": "bun", "ecosystem": "go", "match": "^bun"}, {"name": "otel", "ecosystem": "go", "match": "^otel"}],
		"consumers": {"go": [{"repo": "a/b", "module": "m"}]},
		"exceptions": [{"checks": ["downgrade"], "package": "x", "reason": "infra", "until": "2026-11-20"}]}`
	repo := `{"version": 1, "mode": "enforce",
		"labels": {"levels": {"security": {"license-changed": "fail"}}},
		"ecosystems": {"maven": {"roots": ["."], "checks": {"downgrade": {"level": "fail", "ignore": [{"package": "b", "reason": "repo"}]}}}},
		"checks": {"license-not-allowed": {"allow": ["Apache-2.0"]}},
		"families": [{"name": "otel", "ecosystem": "go", "match": "^go.opentelemetry.io/otel", "key": "stable"}],
		"consumers": false,
		"exceptions": [{"checks": ["downgrade"], "package": "y", "reason": "repo", "until": "2026-11-20"}]}`
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, RepoConfigFile), repo)
	p := load(t, LoadOptions{Defaults: []byte(infra), DefaultsName: "infra", RepoDir: dir})
	if len(p.Problems) > 0 {
		t.Fatal(p.Problems[0].Message)
	}
	c := p.Config
	if c.Mode != ModeEnforce || c.MaxExceptionDays != 30 {
		t.Errorf("scalars: %+v", c)
	}
	if diff := cmp.Diff(map[string]map[string]Level{"security": {"vuln-new": Fail, "license-changed": Fail}}, c.Labels.Levels); diff != "" {
		t.Errorf("label levels (-want +got):\n%s", diff)
	}
	down := c.Ecosystems[Maven].Checks["downgrade"]
	if down.Level != Fail || len(down.Ignore) != 2 {
		t.Errorf("ecosystem check %+v", down)
	}
	var pins string
	var roots []string
	if err := c.Ecosystems[Maven].Setting("pins", &pins); err != nil || pins != "infra.json" {
		t.Errorf("pins %q %v", pins, err)
	}
	if err := c.Ecosystems[Maven].Setting("roots", &roots); err != nil || !slices.Equal(roots, []string{"."}) {
		t.Errorf("roots %v %v", roots, err)
	}
	if diff := cmp.Diff(CheckSettings{Level: Warn, Allow: []string{"Apache-2.0"}}, c.Checks["license-not-allowed"]); diff != "" {
		t.Errorf("license check (-want +got):\n%s", diff)
	}
	if diff := cmp.Diff([]Family{
		{Name: "bun", Ecosystem: Go, Match: "^bun"},
		{Name: "otel", Ecosystem: Go, Match: "^go.opentelemetry.io/otel", Key: "stable"},
	}, c.Families); diff != "" {
		t.Errorf("families (-want +got):\n%s", diff)
	}
	if string(c.Consumers) != "false" {
		t.Errorf("consumers %s", c.Consumers)
	}
	if len(c.Exceptions) != 2 || c.Exceptions[0].origin.String() != "infra#/exceptions/0" ||
		c.Exceptions[1].origin.String() != RepoConfigFile+"#/exceptions/0" {
		t.Errorf("exceptions %+v", c.Exceptions)
	}
	// the effective configuration is valid config.v1 for the adapters
	data, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	if v, err := schemaViolations(testSchemas(t).config, data); err != nil || len(v) > 0 {
		t.Errorf("effective config: %v %v\n%s", v, err, data)
	}

	// infra and repository ignore rules both apply, with their sources
	r := p.Evaluate(Input{Head: []*FindingsFile{headFile("maven-pins", Maven, AdapterOK, []string{"downgrade"},
		Item{Check: "downgrade", Package: "a", Message: "m"}, Item{Check: "downgrade", Package: "b", Message: "m"},
		Item{Check: "downgrade", Package: "y", Message: "m"})}})
	expectFindings(t, r, want{"downgrade", "a", Fail, Allowed}, want{"downgrade", "b", Fail, Allowed}, want{"downgrade", "y", Fail, Allowed})
	if got := find(t, r, "downgrade", "a").Exception.Source; got != "infra#/ecosystems/maven/checks/downgrade/ignore/0" {
		t.Errorf("source %s", got)
	}
	// infra exceptions are reported without a repository location
	if unused := find(t, r, ExceptionUnused, "infra#"); len(unused.Locations) != 0 {
		t.Errorf("locations %v", unused.Locations)
	}
}

func TestReportMatchesSchema(t *testing.T) {
	full := policy(t, repoFixture(t)).Evaluate(fixtures(t))
	full.Repository, full.PullRequest, full.Gate = "Netcracker/qubership-core-java-libs", 230, "qubership-core-infra@test"
	for name, r := range map[string]*Report{"full": full, "empty": policy(t, "").Evaluate(Input{})} {
		t.Run(name, func(t *testing.T) {
			data, err := json.Marshal(r)
			if err != nil {
				t.Fatal(err)
			}
			if err := testSchemas(t).ValidateReport(data); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestTransitive(t *testing.T) {
	declared := Item{Check: "pin-exceeded", Package: "com.fasterxml.jackson.core:jackson-databind", Version: "2.21.8", Message: "above the pin"}
	tree := declared
	tree.Transitive = true
	other := Item{Check: "pin-exceeded", Package: "io.netty:netty-codec", Version: "4.2.9", Message: "above the pin", Transitive: true}
	base := headFile("maven-pins", Maven, AdapterOK, nil, other)
	base.Revision = Base
	in := Input{
		Head: []*FindingsFile{headFile("maven-pins", Maven, AdapterOK, []string{"pin-exceeded"}, declared, tree, other)},
		Base: []*FindingsFile{base},
	}
	const (
		declaredKey = "maven|pin-exceeded|com.fasterxml.jackson.core:jackson-databind|2.21.8|"
		treeKey     = declaredKey + "|transitive"
		otherKey    = "maven|pin-exceeded|io.netty:netty-codec|4.2.9||transitive"
	)
	for _, tc := range []struct {
		name, config string
		want         []string
	}{
		// infra: transitive findings are at most warnings, keyed apart from declared ones
		{"infra defaults", "", []string{declaredKey + " fail new", treeKey + " warn new", otherKey + " warn preexisting"}},
		{"enforced", `{"version": 1, "ecosystems": {"maven": {"transitive": "fail"}}}`,
			[]string{declaredKey + " fail new", treeKey + " fail new", otherKey + " warn preexisting"}},
		{"off", `{"version": 1, "ecosystems": {"maven": {"transitive": "off"}}}`, []string{declaredKey + " fail new"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := policy(t, tc.config).Evaluate(in)
			if diff := cmp.Diff(tc.want, keys(r)); diff != "" {
				t.Errorf("findings (-want +got):\n%s", diff)
			}
			if len(r.Findings) > 1 && !r.Findings[1].Transitive {
				t.Error("the report keeps the flag")
			}
		})
	}
}

func TestAdapterOfSeveralEcosystems(t *testing.T) {
	osv := headFile("osv", "", AdapterOK, []string{"vuln-new"},
		Item{Ecosystem: Maven, Check: "vuln-new", Package: "org.yaml:snakeyaml", Version: "2.4", Vuln: "GHSA-1", Message: "new advisory"},
		Item{Ecosystem: Go, Check: "vuln-new", Package: "golang.org/x/net", Version: "v0.40.0", Vuln: "GO-2026-1", Message: "new advisory"},
		Item{Check: "vuln-new", Package: "left-pad", Message: "no ecosystem"},
		Item{Ecosystem: Go, Check: "pin-exceeded", Package: "x", Message: "a maven check"},
	)
	base := headFile("osv", "", AdapterOK, nil,
		Item{Ecosystem: Go, Check: "vuln-new", Package: "golang.org/x/net", Version: "v0.40.0", Vuln: "GO-2026-1", Message: "old"})
	base.Revision = Base
	r := policy(t, `{"version": 1, "ecosystems": {"go": {"checks": {"vuln-new": "fail"}}}}`).Evaluate(
		Input{Head: []*FindingsFile{osv}, Base: []*FindingsFile{base}})

	expectFindings(t, r, want{"vuln-new", "snakeyaml", Warn, New}, want{"vuln-new", "x/net", Warn, Preexisting})
	if netx := find(t, r, "vuln-new", "x/net"); netx.Ecosystem != Go || netx.Key != "go|vuln-new|golang.org/x/net|v0.40.0|" {
		t.Errorf("%+v", netx)
	}
	var toolErrors []string
	for _, f := range matching(r, ToolError, "") {
		toolErrors = append(toolErrors, f.Level.String()+" "+string(f.Status)+" "+f.Message)
	}
	if diff := cmp.Diff([]string{`warn new finding "vuln-new" has no ecosystem`, `warn new unknown go check "pin-exceeded"`}, toolErrors); diff != "" {
		t.Errorf("tool errors (-want +got):\n%s", diff)
	}
}

// A repository can except a locked check only for one package version at a time, so it cannot switch the gate off
// or let the next bump of the package through.
func TestLockedExceptionsNeedAPackageVersion(t *testing.T) {
	p := policy(t, `{"version": 1, "mode": "enforce", "exceptions": [
		{"checks": ["*"], "package": "*", "version": "1.0", "reason": "r", "until": "2026-12-01"},
		{"checks": ["pin-*"], "package": "/^com[.]rabbitmq:/", "version": "5.37.0", "reason": "r", "until": "2026-12-01"},
		{"checks": ["feature-snapshot"], "path": "pom.xml", "reason": "r", "until": "2026-12-01"},
		{"checks": ["pin-*"], "package": "com.rabbitmq:amqp-client", "reason": "any version", "until": "2026-12-01"},
		{"checks": ["pin-*"], "package": "com.rabbitmq:amqp-client", "version": "*", "reason": "r", "until": "2026-12-01"},
		{"checks": ["pin-*"], "package": "com.rabbitmq:amqp-client", "version": "/^5[.]/", "reason": "r", "until": "2026-12-01"},
		{"checks": ["pin-lagging", "mod-*"], "package": "*", "reason": "not locked", "until": "2026-12-01"},
		{"checks": ["*"], "package": "com.rabbitmq:amqp-client", "version": "5.37.0", "reason": "one version", "until": "2026-12-01"}
	]}`)
	if diff := cmp.Diff(map[string]string{
		"#/exceptions/0": ExceptionInvalid, "#/exceptions/1": ExceptionInvalid, "#/exceptions/2": ExceptionInvalid,
		"#/exceptions/3": ExceptionInvalid, "#/exceptions/4": ExceptionInvalid, "#/exceptions/5": ExceptionInvalid,
	}, problems(p)); diff != "" {
		t.Errorf("problems (-want +got):\n%s", diff)
	}

	r := p.Evaluate(fixtures(t))
	expectFindings(t, r,
		want{"pin-exceeded", "jackson-bom", Fail, New},
		want{"pin-exceeded", "amqp-client", Fail, Allowed},
		want{"pin-lagging", "slf4j-api", Warn, Allowed},
	)
	if r.Verdict != VerdictFail || !r.Failed() {
		t.Errorf("verdict %s, failed %v: %v", r.Verdict, r.Failed(), keys(r))
	}

	infra := strings.Replace(string(infraDefaults(t)), `"version": 1,`,
		`"version": 1, "exceptions": [{"checks": ["pin-*"], "package": "/^com[.]rabbitmq:/", "reason": "infra", "until": "2026-12-01"}],`, 1)
	if q := load(t, LoadOptions{Defaults: []byte(infra), DefaultsName: "infra", RepoDir: t.TempDir()}); len(q.Problems) > 0 {
		t.Error(q.Problems[0].Message)
	}
}

// On pull requests the repository file comes from the base revision: a change cannot relax its own gate.
func TestRepoConfigAtRevision(t *testing.T) {
	dir := t.TempDir()
	git := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"}, args...)...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	git("init", "-q")
	writeFile(t, filepath.Join(dir, "README.md"), "x")
	git("add", ".")
	git("commit", "-q", "-m", "no config")
	writeFile(t, filepath.Join(dir, RepoConfigFile), `{"version": 1, "mode": "enforce"}`)
	git("add", ".")
	git("commit", "-q", "-m", "enforce")
	writeFile(t, filepath.Join(dir, RepoConfigFile), `{"version": 1, "mode": "report", "checks": {"pin-lagging": "off"}}`)
	git("commit", "-q", "-am", "relax")

	base := load(t, LoadOptions{RepoDir: dir, RepoRev: "HEAD^1"})
	if base.Config.Mode != ModeEnforce || !slices.Equal(base.Sources, []string{"infra:defaults.json", RepoConfigFile + "@HEAD^1"}) {
		t.Errorf("base: mode %s, sources %v", base.Config.Mode, base.Sources)
	}
	unknown := load(t, LoadOptions{RepoDir: dir, RepoRev: "HEAD~5"})
	if unknown.Config.Mode != ModeReport || len(unknown.Problems) != 1 || unknown.Problems[0].Check != ConfigInvalid {
		t.Errorf("unknown revision: mode %s, problems %+v", unknown.Config.Mode, unknown.Problems)
	}
}
