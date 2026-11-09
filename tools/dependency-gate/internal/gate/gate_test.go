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

func TestDefaultsAreValid(t *testing.T) {
	p := policy(t, "")
	if len(p.Problems) > 0 {
		t.Fatal(p.Problems)
	}
	if p.Config.Mode != ModeReport || p.Config.MaxExceptionDays != 0 || len(p.Config.Locked) != 0 {
		t.Errorf("unexpected defaults: %+v", p.Config)
	}
}

func TestFixturePolicy(t *testing.T) {
	r := policy(t, repoFixture(t)).Evaluate(fixtures(t))
	expectFindings(t, r,
		want{"apidiff-exposed", "openshift", Fail, Allowed},
		want{"apidiff-exposed", "opensearch-go", Fail, New},
	)

	if got := find(t, r, "apidiff-exposed", "openshift").Exception; got.Source != RepoConfigFile+"#/checks/apidiff-exposed/ignore/0" || got.Until != "" {
		t.Errorf("ignore rule: %+v", got)
	}
	if r.Verdict != VerdictFail || r.Mode != ModeReport || r.Failed() {
		t.Errorf("verdict %s in %s mode, failed %v", r.Verdict, r.Mode, r.Failed())
	}
	if r.Counts.Allowed != 1 || r.Counts.Preexisting != 0 {
		t.Errorf("counts %+v", r.Counts)
	}
	if diff := cmp.Diff([]string{"infra:defaults.json", RepoConfigFile}, r.Config); diff != "" {
		t.Errorf("config sources (-want +got):\n%s", diff)
	}
	// the repository narrows what the adapters check, locked checks included: the report shows it
	if diff := cmp.Diff(map[string]Scope{
		Go: {Modules: []string{"."}},
	}, r.Scope); diff != "" {
		t.Errorf("scope (-want +got):\n%s", diff)
	}
	if r.Findings[0].Level != Fail || !slices.IsSortedFunc(r.Findings, compareFindings) {
		t.Errorf("findings are not ordered by importance: %v", keys(r))
	}
}

func TestBaseline(t *testing.T) {
	old := Item{Check: "apidiff-exposed", Package: "github.com/example/old", Version: "v1.0.0", Symbol: "T", Message: "removed"}
	bumped := Item{Check: "apidiff-exposed", Package: "github.com/example/new", Version: "v1.1.0", Symbol: "T", Message: "removed"}
	base := headFile("go-library", Go, AdapterOK, nil, old)
	base.Revision = Base
	r := policy(t, "").Evaluate(Input{
		Head: []*FindingsFile{headFile("go-library", Go, AdapterOK, []string{"apidiff-exposed"}, old, bumped)},
		Base: []*FindingsFile{base},
	})
	expectFindings(t, r,
		want{"apidiff-exposed", "example/old", Warn, Preexisting},
		want{"apidiff-exposed", "example/new", Fail, New},
	)
	if r.Counts.Preexisting != 1 {
		t.Errorf("counts %+v", r.Counts)
	}
	if got := find(t, r, "apidiff-exposed", "example/old").Key; got != "go|apidiff-exposed|github.com/example/old|v1.0.0|T" {
		t.Errorf("key %q", got)
	}
}

func TestModes(t *testing.T) {
	found := Input{Head: []*FindingsFile{headFile("go-library", Go, AdapterOK, []string{"apidiff-exposed"},
		Item{Check: "apidiff-exposed", Package: "g", Version: "v2", Symbol: "T", Message: "removed"})}}
	// report mode stays green with a fail verdict — covered by TestFixturePolicy
	if r := policy(t, `{"version": 1, "mode": "enforce"}`).Evaluate(found); r.Verdict != VerdictFail || !r.Failed() {
		t.Errorf("enforce: verdict %s, failed %v", r.Verdict, r.Failed())
	}
	if r := policy(t, `{"version": 1, "mode": "enforce"}`).Evaluate(Input{}); r.Verdict != VerdictPass || r.Failed() {
		t.Errorf("nothing found: verdict %s, failed %v", r.Verdict, r.Failed())
	}
}

func TestLabelLevels(t *testing.T) {
	p := policy(t, `{"version": 1, "checks": {"family-split": "off"}, "labels": {"levels": {"security": {"family-split": "fail"}}}}`)
	for _, tc := range []struct {
		labels []string
		level  Level
	}{
		{nil, Off},
		{[]string{"automerge", "security"}, Fail},
	} {
		t.Run(fmt.Sprint(tc.labels), func(t *testing.T) {
			in := Input{Labels: tc.labels, Head: []*FindingsFile{headFile("go-library", Go, AdapterOK, []string{"family-split"},
				Item{Check: "family-split", Package: "github.com/example/mod", Message: "versions diverge"})}}
			if tc.level == Off {
				expectFindings(t, p.Evaluate(in), want{check: "family-split", pkg: "example/mod"})
				return
			}
			expectFindings(t, p.Evaluate(in), want{"family-split", "example/mod", tc.level, New})
		})
	}
}

func TestEcosystemLevels(t *testing.T) {
	item := Item{Check: "consumer-build", Package: "p", Version: "1", Message: "does not build"}
	both := Input{Head: []*FindingsFile{headFile("maven-consumers", Maven, AdapterOK, nil, item), headFile("go-consumers", Go, AdapterOK, nil, item)}}
	r := policy(t, `{"version": 1, "checks": {"consumer-build": "fail"}, "ecosystems": {"go": {"checks": {"consumer-build": "info"}}}}`).Evaluate(both)
	if diff := cmp.Diff([]string{"maven|consumer-build|p|1| fail new", "go|consumer-build|p|1| info new"}, keys(r)); diff != "" {
		t.Errorf("levels (-want +got):\n%s", diff)
	}
	// a check that is off is not reported at all
	expectFindings(t, policy(t, `{"version": 1, "checks": {"consumer-build": "off"}}`).Evaluate(both), want{check: "consumer-build"})
}

func TestToolErrors(t *testing.T) {
	in := Input{
		Head: []*FindingsFile{
			headFile("osv", Maven, AdapterError, nil),
			headFile("go-library", Go, AdapterOK, []string{"apidiff-exposed"}, Item{Check: "no-such-check", Message: "wrong check"}),
			headFile("go-consumers", Go, AdapterSkipped, nil),
		},
		Expected: map[string]string{"missing": "failure", "detect": "success", "go-consumers": "success", "go-other": "skipped", "go-plan": "skipped: non-test build list unchanged"},
	}
	for _, tc := range []struct {
		name, config string
	}{
		{"infra defaults", ""},
		{"raised by the repository", `{"version": 1, "checks": {"tool-error": "fail"}}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := policy(t, tc.config).Evaluate(in)
			expectFindings(t, r,
				// fail closed: a failed adapter, an adapter that wrote nothing, a broken contract.
				// go-consumers is the soft adapter: a skipped matrix is not an error.
				want{ToolError, "missing", Fail, New},
				want{ToolError, "detect", Fail, New},
				want{ToolError, "go-library", Fail, New},
				want{ToolError, "osv", Fail, New},
				want{check: ToolError, pkg: "go-consumers"},
				want{check: ToolError, pkg: "go-other"},
				want{check: ToolError, pkg: "go-plan"},
			)
			i := slices.IndexFunc(r.Adapters, func(a Adapter) bool { return a.Name == "go-other" })
			if i < 0 || r.Adapters[i].Status != AdapterSkipped || r.Adapters[i].Reason != "job skipped" {
				t.Errorf("adapters %+v", r.Adapters)
			}
			i = slices.IndexFunc(r.Adapters, func(a Adapter) bool { return a.Name == "go-plan" })
			if i < 0 || r.Adapters[i].Status != AdapterSkipped || r.Adapters[i].Reason != "non-test build list unchanged" {
				t.Errorf("plan skip %+v", r.Adapters)
			}
		})
	}
}

func TestBrokenFindingsFile(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "library-head.json"),
		`{"schema": "dependency-gate-findings/v1", "adapter": {"name": "library", "ecosystem": "go", "status": "ok"}, "revision": "head", "findings": []}`)
	writeFile(t, filepath.Join(dir, "sub", "broken.json"), `{"findings": [`)
	var in Input
	if err := in.ReadFindings(testSchemas(t), dir); err != nil {
		t.Fatal(err)
	}
	r := policy(t, "").Evaluate(in)
	expectFindings(t, r, want{ToolError, "library", Fail, New}, want{ToolError, "broken", Fail, New})
	if msg := find(t, r, ToolError, "library").Message; !strings.Contains(msg, "checks") {
		t.Errorf("message should name the missing field: %s", msg)
	}
}

func TestInvalidRepoConfig(t *testing.T) {
	for name, cfg := range map[string]string{
		"not json":           `{"version": 1,`,
		"schema":             `{"version": 1, "mode": "strict"}`,
		"unknown check":      `{"version": 1, "checks": {"no-such-check": "warn"}}`,
		"wrong ecosystem":    `{"version": 1, "ecosystems": {"maven": {"checks": {"consumer-test-flaky": "warn"}}}}`,
		"gate check off":     `{"version": 1, "checks": {"exception-unused": "off"}}`,
		"gate check lowered": `{"version": 1, "checks": {"tool-error": "info"}}`,
		"gate check warned":  `{"version": 1, "checks": {"config-invalid": "warn"}}`,
		"infra only":         `{"version": 1, "maxExceptionDays": 365}`,
		"exceptions":         `{"version": 1, "exceptions": [{"checks": ["consumer-*"], "package": "g:a", "version": "1", "reason": "r", "until": "2026-12-01"}]}`,
		"unknown setting":    `{"version": 1, "ecosystems": {"maven": {"plugin": "x"}}}`,
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

func TestLockedCheck(t *testing.T) {
	infra := `{"version": 1, "locked": ["apidiff-exposed"]}`
	for name, cfg := range map[string]string{
		"weakened": `{"version": 1, "checks": {"apidiff-exposed": "warn"}}`,
		"ignore":   `{"version": 1, "checks": {"apidiff-exposed": {"ignore": [{"package": "g", "reason": "r"}]}}}`,
		"by eco":   `{"version": 1, "ecosystems": {"go": {"checks": {"apidiff-exposed": "warn"}}}}`,
		"by label": `{"version": 1, "labels": {"levels": {"security": {"apidiff-exposed": "warn"}}}}`,
	} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			writeFile(t, filepath.Join(dir, RepoConfigFile), cfg)
			p := load(t, LoadOptions{Defaults: []byte(infra), DefaultsName: "infra", RepoDir: dir})
			if len(p.Problems) != 1 || p.Problems[0].Check != ConfigInvalid {
				t.Fatalf("problems %+v", p.Problems)
			}
		})
	}
}

func TestExceptionsAreRejected(t *testing.T) {
	p := policy(t, `{"version": 1, "mode": "enforce", "exceptions": [
		{"checks": ["consumer-*"], "package": "g:a", "version": "1", "reason": "r", "until": "2026-12-01"}
	]}`)
	if len(p.Problems) != 1 || p.Problems[0].Check != ConfigInvalid || !strings.Contains(p.Problems[0].Message, "exceptions") {
		t.Fatalf("%+v", p.Problems)
	}
	if slices.Contains(p.Sources, RepoConfigFile) {
		t.Fatal("a file with exceptions is ignored")
	}
	r := p.Evaluate(fixtures(t))
	expectFindings(t, r,
		want{"apidiff-exposed", "openshift", Fail, New},
		want{"apidiff-exposed", "opensearch-go", Fail, New},
	)
	if r.Mode != ModeReport || r.Verdict != VerdictFail || r.Failed() {
		t.Errorf("the ignored file must not switch the mode: %s %s failed=%v", r.Mode, r.Verdict, r.Failed())
	}
}

func TestMerge(t *testing.T) {
	infra := `{"version": 1, "mode": "report", "maxExceptionDays": 30,
		"labels": {"levels": {"security": {"family-split": "fail"}}},
		"ecosystems": {"go": {"checks": {"family-split": {"level": "warn", "ignore": [{"package": "a", "reason": "infra"}]}}}},
		"checks": {"consumer-test-flaky": "warn"},
		"families": [{"name": "bun", "ecosystem": "go", "match": "^bun"}, {"name": "otel", "ecosystem": "go", "match": "^otel"}],
		"consumers": {"go": [{"repo": "a/b", "module": "m"}]}}`
	repo := `{"version": 1, "mode": "enforce",
		"labels": {"levels": {"security": {"apidiff-exposed": "fail"}}},
		"ecosystems": {"maven": {"roots": ["."]}, "go": {"checks": {"family-split": {"level": "fail", "ignore": [{"package": "b", "reason": "repo"}]}}}},
		"checks": {"consumer-test-flaky": "info"},
		"families": [{"name": "otel", "ecosystem": "go", "match": "^go.opentelemetry.io/otel", "key": "stable"}],
		"consumers": false}`
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
	if diff := cmp.Diff(map[string]map[string]Level{"security": {"family-split": Fail, "apidiff-exposed": Fail}}, c.Labels.Levels); diff != "" {
		t.Errorf("label levels (-want +got):\n%s", diff)
	}
	down := c.Ecosystems[Go].Checks["family-split"]
	if down.Level != Fail || len(down.Ignore) != 2 {
		t.Errorf("ecosystem check %+v", down)
	}
	var roots []string
	if err := c.Ecosystems[Maven].Setting("roots", &roots); err != nil || !slices.Equal(roots, []string{"."}) {
		t.Errorf("roots %v %v", roots, err)
	}
	if diff := cmp.Diff(CheckSettings{Level: Info}, c.Checks["consumer-test-flaky"]); diff != "" {
		t.Errorf("consumer-test-flaky (-want +got):\n%s", diff)
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
	if len(c.Exceptions) != 0 {
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
	r := p.Evaluate(Input{Head: []*FindingsFile{headFile("go-library", Go, AdapterOK, []string{"family-split"},
		Item{Check: "family-split", Package: "a", Message: "m"}, Item{Check: "family-split", Package: "b", Message: "m"},
		Item{Check: "family-split", Package: "y", Message: "m"})}})
	expectFindings(t, r, want{"family-split", "a", Fail, Allowed}, want{"family-split", "b", Fail, Allowed}, want{"family-split", "y", Fail, New})
	if got := find(t, r, "family-split", "a").Exception.Source; got != "infra#/ecosystems/go/checks/family-split/ignore/0" {
		t.Errorf("source %s", got)
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

func TestAdapterOfSeveralEcosystems(t *testing.T) {
	osv := headFile("osv", "", AdapterOK, []string{"family-split"},
		Item{Ecosystem: Maven, Check: "family-split", Package: "org.yaml:snakeyaml", Version: "2.4", Message: "versions diverge"},
		Item{Ecosystem: Go, Check: "family-split", Package: "golang.org/x/net", Version: "v0.40.0", Message: "versions diverge"},
		Item{Check: "family-split", Package: "left-pad", Message: "no ecosystem"},
		Item{Ecosystem: Go, Check: "no-such-check", Package: "x", Message: "unknown"},
	)
	base := headFile("osv", "", AdapterOK, nil,
		Item{Ecosystem: Go, Check: "family-split", Package: "golang.org/x/net", Version: "v0.40.0", Message: "old"})
	base.Revision = Base
	r := policy(t, `{"version": 1, "ecosystems": {"go": {"checks": {"family-split": "fail"}}}}`).Evaluate(
		Input{Head: []*FindingsFile{osv}, Base: []*FindingsFile{base}})

	// family-split is off for Maven, so that finding is dropped; the Go one is already on the base
	expectFindings(t, r, want{check: "family-split", pkg: "snakeyaml"}, want{"family-split", "x/net", Warn, Preexisting})
	if netx := find(t, r, "family-split", "x/net"); netx.Ecosystem != Go || netx.Key != "go|family-split|golang.org/x/net|v0.40.0|" {
		t.Errorf("%+v", netx)
	}
	var toolErrors []string
	for _, f := range matching(r, ToolError, "") {
		toolErrors = append(toolErrors, f.Level.String()+" "+string(f.Status)+" "+f.Message)
	}
	if diff := cmp.Diff([]string{`fail new finding "family-split" has no ecosystem`, `fail new unknown go check "no-such-check"`}, toolErrors); diff != "" {
		t.Errorf("tool errors (-want +got):\n%s", diff)
	}
}

// Exceptions are not policy, including in the infra defaults: Load refuses them.
func TestInfraExceptionsAreRejected(t *testing.T) {
	infra := strings.Replace(string(infraDefaults(t)), `"version": 1,`,
		`"version": 1, "exceptions": [{"checks": ["consumer-*"], "package": "g:a", "version": "1", "reason": "infra", "until": "2026-12-01"}],`, 1)
	_, err := Load(LoadOptions{Schemas: testSchemas(t), Defaults: []byte(infra), DefaultsName: "infra", RepoDir: t.TempDir(), Today: today})
	if err == nil || !strings.Contains(err.Error(), "exceptions") {
		t.Fatalf("err = %v", err)
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
	writeFile(t, filepath.Join(dir, RepoConfigFile), `{"version": 1, "mode": "report", "checks": {"family-split": "off"}}`)
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
