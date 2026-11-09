package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
)

const fixtures = "internal/gate/testdata/findings"

// githubEnv replaces the GitHub Actions environment; empty values unset it for the test.
func githubEnv(t *testing.T, env map[string]string) {
	t.Helper()
	for _, k := range []string{"GITHUB_ACTIONS", "GITHUB_EVENT_NAME", "GITHUB_EVENT_PATH", "GITHUB_SHA", "GITHUB_REF_NAME",
		"GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_SERVER_URL", "GITHUB_API_URL", "GITHUB_TOKEN", "GITHUB_OUTPUT",
		"GITHUB_STEP_SUMMARY"} {
		t.Setenv(k, env[k])
	}
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// pullRequestRepo is a git repository like the checkout of a pull request: HEAD^1 is the base branch with
// baseConfig, HEAD changes a pom and has headConfig as the repository file.
func pullRequestRepo(t *testing.T, baseConfig, headConfig string) string {
	t.Helper()
	dir := t.TempDir()
	git := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"}, args...)...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	git("init", "-q")
	write(t, filepath.Join(dir, "pom.xml"), "<project/>")
	write(t, filepath.Join(dir, gate.RepoConfigFile), baseConfig)
	git("add", ".")
	git("commit", "-q", "-m", "base")
	write(t, filepath.Join(dir, "pom.xml"), "<project><!-- bump --></project>")
	write(t, filepath.Join(dir, gate.RepoConfigFile), headConfig)
	git("commit", "-q", "-am", "head")
	return dir
}

func eventFile(t *testing.T, baseRef string, labels ...string) string {
	t.Helper()
	var names []map[string]string
	for _, l := range labels {
		names = append(names, map[string]string{"name": l})
	}
	data, _ := json.Marshal(map[string]any{"action": "synchronize", "pull_request": map[string]any{
		"number": 7, "labels": names,
		"base": map[string]any{"ref": baseRef, "sha": "base-sha", "repo": map[string]any{"full_name": "o/r"}},
		"head": map[string]any{"ref": "renovate/x", "sha": "head-sha", "repo": map[string]any{"full_name": "o/r"}},
	}})
	path := filepath.Join(t.TempDir(), "event.json")
	write(t, path, string(data))
	return path
}

func readReport(t *testing.T, dir string) *gate.Report {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "report.json"))
	if err != nil {
		t.Fatal(err)
	}
	var r gate.Report
	if err := json.Unmarshal(data, &r); err != nil {
		t.Fatal(err)
	}
	return &r
}

func has(r *gate.Report, check string) bool {
	return slices.ContainsFunc(r.Findings, func(f *gate.Finding) bool { return f.Check == check })
}

func TestEvaluateLocal(t *testing.T) {
	githubEnv(t, nil)
	repo, out := t.TempDir(), t.TempDir()
	var stdout bytes.Buffer
	err := run(t.Context(), "evaluate", []string{"-repo", repo, "-findings", fixtures, "-out", out, "-today", "2026-10-29",
		"-branch", "feature/x", "-expect", "maven-pins=success"}, &stdout)
	if err != nil {
		t.Fatal(err)
	}
	r := readReport(t, out)
	// feature SNAPSHOT versions are allowed outside protected branches
	if r.Mode != gate.ModeReport || has(r, "feature-snapshot") {
		t.Errorf("report: mode %s, findings %d", r.Mode, len(r.Findings))
	}
	if !strings.HasPrefix(stdout.String(), "### ") || strings.Contains(stdout.String(), "::") {
		t.Errorf("locally the summary is printed, no workflow commands:\n%s", stdout.String())
	}
	for _, name := range []string{"summary.md", "comment.md"} {
		if _, err := os.Stat(filepath.Join(out, name)); err != nil {
			t.Error(err)
		}
	}

	write(t, filepath.Join(repo, gate.RepoConfigFile), `{"version": 1, "mode": "enforce"}`)
	err = run(t.Context(), "evaluate", []string{"-repo", repo, "-findings", fixtures, "-today", "2026-10-29"}, &stdout)
	if !errors.Is(err, errFailed) {
		t.Errorf("a fail verdict in enforce mode: got %v", err)
	}
}

// On a pull request the repository file of the base branch applies: the change cannot relax its own gate.
func TestEvaluatePullRequest(t *testing.T) {
	repo := pullRequestRepo(t, `{"version": 1, "mode": "enforce"}`, `{"version": 1, "mode": "report"}`)
	work := t.TempDir()
	env := map[string]string{
		"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": "o/r", "GITHUB_SHA": "merge-sha",
		"GITHUB_EVENT_PATH":   eventFile(t, "feature/x"),
		"GITHUB_OUTPUT":       filepath.Join(work, "output"),
		"GITHUB_STEP_SUMMARY": filepath.Join(work, "summary"),
	}
	githubEnv(t, env)
	out := t.TempDir()
	var stdout bytes.Buffer
	err := run(t.Context(), "evaluate", []string{"-repo", repo, "-findings", fixtures, "-out", out, "-today", "2026-10-29",
		"-comment", "-gate", "qubership-core-infra@test"}, &stdout)
	if !errors.Is(err, errFailed) {
		t.Fatalf("the base branch enforces: got %v", err)
	}
	r := readReport(t, out)
	if r.PullRequest != 7 || r.Base != "base-sha" || r.Head != "head-sha" || r.Repository != "o/r" || r.Gate != "qubership-core-infra@test" {
		t.Errorf("report header %+v", r)
	}
	if !slices.Equal(r.Config, []string{"infra:defaults.json@dev", gate.RepoConfigFile + "@HEAD^1"}) || r.Mode != gate.ModeEnforce {
		t.Errorf("config %v, mode %s", r.Config, r.Mode)
	}
	// the target branch comes from the event
	if has(r, "feature-snapshot") {
		t.Error("feature-snapshot on a feature branch")
	}
	if !strings.Contains(stdout.String(), "::error file=") || !strings.Contains(stdout.String(), "::warning::cannot update the PR comment") {
		t.Errorf("annotations and the comment warning:\n%s", stdout.String())
	}
	output, _ := os.ReadFile(env["GITHUB_OUTPUT"])
	summary, _ := os.ReadFile(env["GITHUB_STEP_SUMMARY"])
	lines := strings.Fields(string(output))
	if len(lines) != 3 || !strings.HasPrefix(lines[0], "verdict<<") || lines[1] != "fail" ||
		!strings.HasPrefix(string(summary), "### ❌ Dependency gate: failed") {
		t.Errorf("output %q, summary %q", output, summary)
	}
}

// With a token the sticky comment is created.
func TestEvaluateComment(t *testing.T) {
	a := newAPI(t, map[string]http.HandlerFunc{
		"GET /repos/o/r/issues/7/comments":  reply([]any{}),
		"POST /repos/o/r/issues/7/comments": replyStatus(http.StatusCreated, map[string]any{"id": 1}),
	})
	repo := pullRequestRepo(t, `{"version": 1}`, `{"version": 1}`)
	work := t.TempDir()
	githubEnv(t, map[string]string{
		"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": "o/r", "GITHUB_TOKEN": "token",
		"GITHUB_API_URL": a.url, "GITHUB_EVENT_PATH": eventFile(t, "main"),
		"GITHUB_OUTPUT": filepath.Join(work, "output"), "GITHUB_STEP_SUMMARY": filepath.Join(work, "summary"),
	})
	var stdout bytes.Buffer
	if err := run(t.Context(), "evaluate", []string{"-repo", repo, "-findings", fixtures, "-today", "2026-10-29", "-comment"}, &stdout); err != nil {
		t.Fatalf("%v\n%s", err, stdout.String())
	}
	if !slices.Contains(a.calls, "POST /repos/o/r/issues/7/comments") {
		t.Errorf("new findings create the comment: %v", a.calls)
	}
}

// The consumers check adds one line to the sticky comment and does not paste its findings there.
func TestEvaluateCommentNote(t *testing.T) {
	a := newAPI(t, map[string]http.HandlerFunc{
		"GET /repos/o/r/issues/7/comments":  reply([]any{}),
		"POST /repos/o/r/issues/7/comments": replyStatus(http.StatusCreated, map[string]any{"id": 1}),
		"GET /repos/o/r/actions/runs/9/jobs": func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"jobs":[{"id":2,"name":"Consumers","html_url":"https://github.com/o/r/actions/runs/9/job/2"}]}`))
		},
	})
	repo := pullRequestRepo(t, `{"version": 1}`, `{"version": 1}`)
	work := t.TempDir()
	githubEnv(t, map[string]string{
		"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": "o/r", "GITHUB_TOKEN": "token",
		"GITHUB_API_URL": a.url, "GITHUB_EVENT_PATH": eventFile(t, "main"), "GITHUB_RUN_ID": "9",
		"GITHUB_SERVER_URL": "https://github.com",
		"GITHUB_OUTPUT":     filepath.Join(work, "output"), "GITHUB_STEP_SUMMARY": filepath.Join(work, "summary"),
	})
	var stdout bytes.Buffer
	if err := run(t.Context(), "evaluate", []string{"-repo", repo, "-findings", fixtures, "-today", "2026-10-29",
		"-comment-note", "-comment-job", "Consumers"}, &stdout); err != nil {
		t.Fatalf("%v\n%s", err, stdout.String())
	}
	if !strings.Contains(a.body, "**Consumers:** would fail") || !strings.Contains(a.body, "https://github.com/o/r/actions/runs/9/job/2") {
		t.Errorf("consumers line:\n%s", a.body)
	}
	if strings.Contains(a.body, "pin-exceeded") {
		t.Errorf("the line lists findings:\n%s", a.body)
	}
}

// Labels of the event and of -label reach labels.levels of the repository file.
func TestEvaluateLabelLevels(t *testing.T) {
	const config = `{"version": 1, "labels": {"levels": {"security": {"pin-lagging": "fail"}}}}`
	for _, tc := range []struct {
		name  string
		args  []string
		event []string // labels of a pull request event; nil means a local run
		want  gate.Level
	}{
		{name: "no label", want: gate.Warn},
		{name: "flag", args: []string{"-label", "security"}, want: gate.Fail},
		{name: "event", event: []string{"automerge", "security"}, want: gate.Fail},
	} {
		t.Run(tc.name, func(t *testing.T) {
			repo := pullRequestRepo(t, config, config)
			env := map[string]string{}
			if tc.event != nil {
				env = map[string]string{"GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": eventFile(t, "main", tc.event...)}
			}
			githubEnv(t, env)
			out := t.TempDir()
			args := append([]string{"-repo", repo, "-findings", fixtures, "-out", out, "-today", "2026-10-29"}, tc.args...)
			if err := run(t.Context(), "evaluate", args, io.Discard); err != nil {
				t.Fatal(err)
			}
			r := readReport(t, out)
			i := slices.IndexFunc(r.Findings, func(f *gate.Finding) bool { return f.Check == "pin-lagging" })
			if i < 0 || r.Findings[i].Level != tc.want {
				t.Errorf("pin-lagging at %d of %+v, want level %s", i, r.Findings, tc.want)
			}
		})
	}
}

func TestDetect(t *testing.T) {
	repo := pullRequestRepo(t, `{"version": 1, "ecosystems": {"maven": {"excludes": ["^base/"]}}}`,
		`{"version": 1, "ecosystems": {"maven": {"excludes": ["^head/"]}}}`)
	outputs := func() map[string]string {
		t.Helper()
		var stdout bytes.Buffer
		if err := run(t.Context(), "detect", []string{"-repo", repo}, &stdout); err != nil {
			t.Fatal(err)
		}
		var m map[string]string
		if err := json.Unmarshal(stdout.Bytes(), &m); err != nil {
			t.Fatalf("%v: %s", err, stdout.String())
		}
		return m
	}

	githubEnv(t, map[string]string{"GITHUB_EVENT_NAME": "pull_request"})
	o := outputs()
	if o["maven"] != "true" || o["maven-roots"] != `["."]` || o["go"] != "false" || o["go-modules"] != "[]" {
		t.Errorf("pull request: %v", o)
	}
	if !strings.Contains(o["config"], `"^base/"`) {
		t.Errorf("config %s", o["config"])
	}

	githubEnv(t, map[string]string{"GITHUB_EVENT_NAME": "push"})
	if o := outputs(); o["maven"] != "true" || !strings.Contains(o["config"], `"^head/"`) {
		t.Errorf("push: %v", o)
	}

	output := filepath.Join(t.TempDir(), "output")
	githubEnv(t, map[string]string{"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "push", "GITHUB_OUTPUT": output})
	if err := run(t.Context(), "detect", []string{"-repo", repo}, &bytes.Buffer{}); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(output)
	for _, name := range []string{"maven", "maven-roots", "go", "go-modules", "config"} {
		if !strings.Contains(string(data), name+"<<") {
			t.Errorf("output %s missing:\n%s", name, data)
		}
	}
}

func TestFlags(t *testing.T) {
	githubEnv(t, nil)
	for _, tc := range []struct {
		name, cmd string
		args      []string
		is        error
		want      string
	}{
		{"help", "evaluate", []string{"-h"}, flag.ErrHelp, ""},
		{"expect", "evaluate", []string{"-expect", "maven-pins"}, nil, "want adapter=result"},
		{"unknown command", "check", nil, errUsage, ""},
		// an unreadable repository file is a finding of the gate, not an error
		{"config at rev", "evaluate", []string{"-config-rev", "no-such-rev", "-out", t.TempDir()}, nil, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var stdout bytes.Buffer
			err := run(t.Context(), tc.cmd, tc.args, &stdout)
			switch {
			case tc.is != nil:
				if !errors.Is(err, tc.is) {
					t.Errorf("got %v, want %v", err, tc.is)
				}
			case tc.want != "":
				if err == nil || !strings.Contains(err.Error(), tc.want) {
					t.Errorf("got %v, want %q", err, tc.want)
				}
			case err != nil || !strings.Contains(stdout.String(), gate.ConfigInvalid):
				t.Errorf("got %v:\n%s", err, stdout.String())
			}
		})
	}
}

func TestCompare(t *testing.T) {
	for _, tc := range []struct {
		a, b string
		want string
	}{
		{"v1.2.0", "v1.2.0", "0"},
		{"v1.2.0", "v1.10.0", "-1"},
		{"v1.2.0-rc.1", "v1.2.0", "-1"},
		{"v1.2.4-0.20260101000000-abcdef012345", "v1.2.4", "-1"},
		{"1.9", "1.10", "-1"},
		{"1.22", "1.22.0", "-1"},
	} {
		t.Run(tc.a+" "+tc.b, func(t *testing.T) {
			var stdout bytes.Buffer
			if err := run(t.Context(), "compare", []string{tc.a, tc.b}, &stdout); err != nil {
				t.Fatal(err)
			}
			if got := strings.TrimSpace(stdout.String()); got != tc.want {
				t.Errorf("got %s, want %s", got, tc.want)
			}
		})
	}
	if err := run(t.Context(), "compare", []string{"v1"}, io.Discard); err == nil {
		t.Error("compare needs two versions")
	}
}

// goDepCheck returns the path of a go-dep-check script, with slashes for bash, and the gate's schemas.
func goDepCheck(t *testing.T, script string) (string, *gate.Schemas) {
	t.Helper()
	path, err := filepath.Abs("../../.github/actions/go-dep-check/" + script)
	if err != nil {
		t.Fatal(err)
	}
	sub, err := fs.Sub(schemas, "schemas")
	if err != nil {
		t.Fatal(err)
	}
	s, err := gate.LoadSchemas(sub)
	if err != nil {
		t.Fatal(err)
	}
	return filepath.ToSlash(path), s
}

func needTools(t *testing.T, tools ...string) {
	t.Helper()
	for _, tool := range tools {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skipf("%s is not installed", tool)
		}
	}
}

// The go-dep-check scripts write findings.v1 with findings.sh: the files must pass the gate's schema.
func TestFindingsScript(t *testing.T) {
	needTools(t, "bash", "jq")
	script, s := goDepCheck(t, "findings.sh")
	// $1: the jsonl file, then: <status> <reason> <checks> <unit> [<emit args>...]
	const sh = `. "$0"; out=$1 status=$2 reason=$3 checks=$4 UNIT=$5; shift 5
: > "$out.jsonl"
if [ $# -gt 0 ]; then emit "$out.jsonl" "$@"; fi
write_findings "$out" go-library go head "$status" "$reason" "$checks" < "$out.jsonl"`
	for _, tc := range []struct {
		name                         string
		status, reason, checks, unit string
		emit                         []string
		want                         []gate.Item
		checksRan                    []string
	}{
		{"every key", "ok", "", "apidiff-exposed family-split apidiff-exposed", "m", []string{"apidiff-exposed", "incompatible change",
			"package=example.com/m", "version=v1.2.0", "from=v1.1.0", "symbol=T", "vuln=GO-2026-0001",
			"note=x.go:1: undefined\ny.go:2: undefined", "loc=go.mod:7", "loc=sub/x.go"},
			[]gate.Item{{Check: "apidiff-exposed", Message: "incompatible change", Package: "example.com/m", Version: "v1.2.0",
				From: "v1.1.0", Symbol: "T", Vuln: "GO-2026-0001", Note: "x.go:1: undefined\ny.go:2: undefined",
				Locations: []gate.Location{{File: "go.mod", Line: 7, Unit: "m"}, {File: "sub/x.go", Unit: "m"}}}},
			[]string{"apidiff-exposed", "family-split"}},
		{"empty values are left out", "ok", "", "family-split", "", []string{"family-split", "versions diverge", "package=", "loc="},
			[]gate.Item{{Check: "family-split", Message: "versions diverge"}}, []string{"family-split"}},
		{"error", "error", "go-library: interrupted", "", "", nil, []gate.Item{}, []string{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			out := filepath.ToSlash(filepath.Join(dir, "go-library-head.json"))
			args := append([]string{"-c", sh, script, out, tc.status, tc.reason, tc.checks, tc.unit}, tc.emit...)
			if msg, err := exec.Command("bash", args...).CombinedOutput(); err != nil {
				t.Fatalf("%v: %s", err, msg)
			}
			if err := os.Remove(out + ".jsonl"); err != nil {
				t.Fatal(err)
			}
			var in gate.Input
			if err := in.ReadFindings(s, dir); err != nil {
				t.Fatal(err)
			}
			if len(in.Problems) > 0 || len(in.Head) != 1 {
				t.Fatalf("problems %v, head files %d", in.Problems, len(in.Head))
			}
			f := in.Head[0]
			a := f.Adapter
			if a.Name != "go-library" || a.Ecosystem != gate.Go || string(a.Status) != tc.status || a.Reason != tc.reason {
				t.Errorf("adapter %+v", a)
			}
			if diff := cmp.Diff(tc.checksRan, a.Checks); diff != "" {
				t.Errorf("checks (-want +got):\n%s", diff)
			}
			if diff := cmp.Diff(tc.want, f.Findings); diff != "" {
				t.Errorf("findings (-want +got):\n%s", diff)
			}
		})
	}
}

// go vet findings are new by file and message: moved code is not new, a second equal finding is.
func TestNewVetScript(t *testing.T) {
	needTools(t, "bash", "awk")
	script, _ := goDepCheck(t, "findings.sh")
	const unused = "a.go:7:2: result of fmt.Sprintf call not used"
	for _, tc := range []struct {
		name, old, new string
		want           []string
	}{
		{"moved", "# m\n" + unused + "\n", "# m\na.go:12:2: result of fmt.Sprintf call not used\n", nil},
		{"moved and added", unused + "\n", "a.go:12:2: result of fmt.Sprintf call not used\na.go:15: printf: wrong verb\n",
			[]string{"a.go:15: printf: wrong verb"}},
		{"new message", unused + "\n", unused + "\na.go:9: printf: wrong verb\n", []string{"a.go:9: printf: wrong verb"}},
		{"same message twice", unused + "\n", "a.go:3:2: result of fmt.Sprintf call not used\n" + unused + "\n",
			[]string{"a.go:3:2: result of fmt.Sprintf call not used"}},
		{"other file", unused + "\n", "b.go:7:2: result of fmt.Sprintf call not used\n",
			[]string{"b.go:7:2: result of fmt.Sprintf call not used"}},
		{"no base output", "", "go: downloading x v1\n" + unused + "\n", []string{unused}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			old, cur := filepath.Join(dir, "old"), filepath.Join(dir, "new")
			write(t, old, tc.old)
			write(t, cur, tc.new)
			out, err := exec.Command("bash", "-c", `. "$0"; newvet "$1" "$2"`, script,
				filepath.ToSlash(old), filepath.ToSlash(cur)).CombinedOutput()
			if err != nil {
				t.Fatalf("%v: %s", err, out)
			}
			var got []string
			if s := strings.TrimSpace(string(out)); s != "" {
				got = strings.Split(s, "\n")
			}
			if diff := cmp.Diff(tc.want, got); diff != "" {
				t.Errorf("new findings (-want +got):\n%s", diff)
			}
		})
	}
}

// consumer-check.sh offline: the consumer replaces the library with a directory, and its go.sum mentions the
// library only to pass the quick filter. Cases with tests need gotestsum.
func TestConsumerCheckScript(t *testing.T) {
	needTools(t, "bash", "jq", "git", "go")
	script, s := goDepCheck(t, "consumer-check.sh")
	_, err := exec.LookPath("gotestsum")
	gotestsum := err == nil
	const (
		lib    = "package lib\n\nfunc Value() int { return 1 }\n\nfunc Old() {}\n"
		value2 = "package lib\n\nfunc Value() int { return 2 }\n\nfunc Old() {}\n"
		uses   = "package c\n\nimport \"example.com/lib\"\n\nfunc Use() int { lib.Old(); return lib.Value() }\n"
		tests  = "package c\n\nimport (\n\t\"os\"\n\t\"testing\"\n)\n\nvar _ = os.Exit\n"
		value  = tests + `
func TestValue(t *testing.T) {
	if Use() != 1 {
		t.Fatal("Use() != 1")
	}
}
`
	)
	for _, tc := range []struct {
		name          string
		libHead, code string // lib.go at head and c.go of the consumer, default lib and uses
		test          string // c_test.go of the consumer; when set, consumer-check runs the tests
		noSum         bool   // go.sum does not mention the library
		status        gate.AdapterStatus
		reason        string   // part of the reason
		head, base    []string // "<check> <symbol>" of the findings
	}{
		{name: "not in go.sum", noSum: true, status: gate.AdapterSkipped, reason: "not a consumer of example.com/lib"},
		{name: "not compiled in", code: "package c\n", status: gate.AdapterSkipped, reason: "(only in go.sum)"},
		{name: "go list fails", code: "package c\n\nimport _ \"example.com/lib/missing\"\n", status: gate.AdapterError,
			reason: "go list failed: "},
		{name: "no change", status: gate.AdapterOK},
		{name: "head breaks the build", libHead: "package lib\n\nfunc Value() int { return 1 }\n", status: gate.AdapterOK,
			head: []string{"consumer-build build", "consumer-build tests", "consumer-build vet"}},
		{name: "tests pass", test: value, status: gate.AdapterOK},
		{name: "a test fails with head", libHead: value2, test: value, status: gate.AdapterOK,
			head: []string{"consumer-test example.com/c.TestValue"}},
		{name: "a subtest fails its parent", libHead: value2, status: gate.AdapterOK, test: tests + `
func TestSub(t *testing.T) {
	t.Run("one", func(t *testing.T) {
		if Use() != 1 {
			t.Fatal("Use() != 1")
		}
	})
}
`, head: []string{"consumer-test example.com/c.TestSub"}},
		{name: "a test fails on base too", libHead: value2, status: gate.AdapterOK,
			test: value + "\nfunc TestBroken(t *testing.T) { t.Fatal(\"broken\") }\n",
			head: []string{"consumer-test example.com/c.TestBroken", "consumer-test example.com/c.TestValue"},
			base: []string{"consumer-test example.com/c.TestBroken"}},
		{name: "a package fails without a failed test", status: gate.AdapterOK,
			test: tests + "\nfunc TestExit(t *testing.T) { os.Exit(3) }\n",
			head: []string{"consumer-test example.com/c"}, base: []string{"consumer-test example.com/c"}},
		{name: "a test passes on a rerun", status: gate.AdapterOK, test: tests + `
func TestFlaky(t *testing.T) {
	if os.Mkdir(os.Getenv("MARK"), 0o700) == nil {
		t.Fatal("first run")
	}
}
`, head: []string{"consumer-test-flaky example.com/c.TestFlaky"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if tc.test != "" && !gotestsum {
				t.Skip("gotestsum is not installed")
			}
			dir := t.TempDir()
			if tc.libHead == "" {
				tc.libHead = lib
			}
			if tc.code == "" {
				tc.code = uses
			}
			src := map[string]string{
				"lib-base/go.mod": "module example.com/lib\n\ngo 1.22\n",
				"lib-base/lib.go": lib,
				"lib-head/go.mod": "module example.com/lib\n\ngo 1.22\n",
				"lib-head/lib.go": tc.libHead,
				"c/go.mod":        "module example.com/c\n\ngo 1.22\n\nrequire example.com/lib v1.0.0\n\nreplace example.com/lib => ../lib-base\n",
				"c/c.go":          tc.code,
			}
			if tc.test != "" {
				src["c/c_test.go"] = tc.test
			}
			if !tc.noSum {
				src["c/go.sum"] = "example.com/lib v1.0.0/go.mod h1:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\n"
			}
			for name, content := range src {
				write(t, filepath.Join(dir, name), content)
			}
			for _, args := range [][]string{{"init", "-q"}, {"add", "."}, {"commit", "-qm", "c"}} {
				git := exec.Command("git", append([]string{"-C", filepath.Join(dir, "c"), "-c", "user.name=t", "-c", "user.email=t@t",
					"-c", "commit.gpgsign=false"}, args...)...)
				if out, err := git.CombinedOutput(); err != nil {
					t.Fatalf("git %v: %v\n%s", args, err, out)
				}
			}
			out := filepath.Join(dir, "out")
			cmd := exec.Command("bash", script, "c", "lib-base", "lib-head", "out", "o/c/.", strconv.FormatBool(tc.test != ""))
			cmd.Dir = dir
			cmd.Env = append(os.Environ(), "GOPROXY=off", "GOFLAGS=", "GOWORK=off", "MARK="+filepath.Join(dir, "mark"))
			msg, err := cmd.CombinedOutput()
			if code := cmd.ProcessState.ExitCode(); code != 0 && code != 2 {
				t.Fatalf("%v: %s", err, msg)
			}
			var in gate.Input
			if err := in.ReadFindings(s, out); err != nil {
				t.Fatal(err)
			}
			if len(in.Problems) > 0 || len(in.Head) != 1 {
				t.Fatalf("problems %v, head files %d\n%s", in.Problems, len(in.Head), msg)
			}
			if a := in.Head[0].Adapter; a.Status != tc.status || !strings.Contains(a.Reason, tc.reason) {
				t.Errorf("adapter %s %q, want %s %q\n%s", a.Status, a.Reason, tc.status, tc.reason, msg)
			}
			keys := func(files []*gate.FindingsFile) []string {
				var k []string
				for _, f := range files {
					for _, i := range f.Findings {
						k = append(k, i.Check+" "+i.Symbol)
					}
				}
				return k
			}
			if diff := cmp.Diff(tc.head, keys(in.Head)); diff != "" {
				t.Errorf("head (-want +got):\n%s\n%s", diff, msg)
			}
			if diff := cmp.Diff(tc.base, keys(in.Base)); diff != "" {
				t.Errorf("base (-want +got):\n%s\n%s", diff, msg)
			}
		})
	}
}

// plan-consumers.sh: the matrix follows the non-test build list. A dependency imported only by tests, or only
// by a package that only tests import, does not plan a consumer. curl is a fake on PATH.
func TestPlanConsumers(t *testing.T) {
	needTools(t, "bash", "jq", "git", "go")
	script, _ := goDepCheck(t, "plan-consumers.sh")
	dir := t.TempDir()
	repo := filepath.Join(dir, "repo")
	bin := filepath.Join(dir, "bin")
	write(t, filepath.Join(bin, "curl"), "#!/bin/sh\nurl=\nfor a in \"$@\"; do\n  case $a in http*) url=$a ;; esac\ndone\n"+
		"case \"${PLAN_CURL:-fail}\" in\n  hit) printf '%s\\n' \"example.com/lib v0.1.0 h1:x\" ;;\n"+
		"  miss) printf '%s\\n' \"example.com/other v0.1.0 h1:x\" ;;\n  *) exit 1 ;;\nesac\n")
	if err := os.Chmod(filepath.Join(bin, "curl"), 0o755); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"prod", "onlytest", "helponly"} {
		write(t, filepath.Join(repo, "third", name, "go.mod"), "module example.com/"+name+"\n\ngo 1.22\n")
		write(t, filepath.Join(repo, "third", name, "p.go"), "package "+name+"\n\nfunc V() int { return 1 }\n")
	}
	const baseMod = `module example.com/lib

go 1.22

require (
	example.com/helponly v0.1.0
	example.com/onlytest v0.1.0
	example.com/prod v0.1.0
)

replace example.com/helponly => ./third/helponly

replace example.com/onlytest => ./third/onlytest

replace example.com/prod => ./third/prod
`
	write(t, filepath.Join(repo, "go.mod"), baseMod)
	write(t, filepath.Join(repo, "use.go"), "package lib\n\nimport \"example.com/prod\"\n\nfunc Use() int { return prod.V() }\n")
	write(t, filepath.Join(repo, "extra", "extra.go"), "package extra\n\nimport _ \"example.com/helponly\"\n")
	write(t, filepath.Join(repo, "use_test.go"), "package lib\n\nimport (\n\t_ \"example.com/lib/extra\"\n\t_ \"example.com/onlytest\"\n\t\"testing\"\n)\n\nfunc TestUse(t *testing.T) {\n\tif Use() != 1 {\n\t\tt.Fatal(Use())\n\t}\n}\n")
	tidy := func() {
		t.Helper()
		cmd := exec.Command("go", "mod", "tidy")
		cmd.Dir = repo
		cmd.Env = append(os.Environ(), "GOPROXY=off", "GOSUMDB=off", "GOWORK=off", "GOTOOLCHAIN=local")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("tidy: %v\n%s", err, out)
		}
	}
	tidy()
	git := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", repo, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"}, args...)...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	git("init", "-q")
	git("add", ".")
	git("commit", "-q", "-m", "base")

	config := `{"consumers":{"go":[{"repo":"example/missing","module":".","tests":true}]}}`
	one := []map[string]any{{"repo": "example/missing", "module": ".", "tests": true, "lib": "."}}
	plan := func(cfg, base, curl string) (stdout, stderr string) {
		t.Helper()
		cmd := exec.Command("bash", script)
		cmd.Env = append(os.Environ(),
			"PATH="+bin+string(os.PathListSeparator)+os.Getenv("PATH"),
			"REPO_DIR="+filepath.ToSlash(repo),
			`MODULES=["."]`,
			"CONFIG="+cfg,
			"BASE_REF="+base,
			"PLAN_CURL="+curl,
			"CURL="+filepath.ToSlash(filepath.Join(bin, "curl")),
			"GOPROXY=off", "GOSUMDB=off", "GOWORK=off", "GOTOOLCHAIN=local",
		)
		var out, errb bytes.Buffer
		cmd.Stdout, cmd.Stderr = &out, &errb
		if err := cmd.Run(); err != nil {
			t.Fatalf("plan: %v\n%s", err, errb.String())
		}
		return out.String(), errb.String()
	}
	none := func(stdout, stderr, note string) {
		t.Helper()
		if strings.TrimSpace(stdout) != "[]" {
			t.Fatalf("%s: got %s\n%s", note, stdout, stderr)
		}
	}
	some := func(stdout, stderr, note string) {
		t.Helper()
		var got []map[string]any
		if err := json.Unmarshal([]byte(stdout), &got); err != nil {
			t.Fatalf("%s: %v\n%s\n%s", note, err, stdout, stderr)
		}
		if diff := cmp.Diff(one, got); diff != "" {
			t.Errorf("%s (-want +got):\n%s\n%s", note, diff, stderr)
		}
	}

	stdout, stderr := plan(`{"consumers":false}`, "HEAD", "hit")
	none(stdout, stderr, "consumers false")
	if strings.Contains(stderr, "plan-reason:") {
		t.Fatalf("consumers false names a reason: %s", stderr)
	}

	stdout, stderr = plan(config, "", "hit")
	some(stdout, stderr, "no base")

	stdout, stderr = plan(config, "HEAD", "hit")
	none(stdout, stderr, "same revision")
	if !strings.Contains(stderr, "plan-reason: non-test build list unchanged") {
		t.Fatalf("same revision: stderr %s", stderr)
	}

	modPath := filepath.Join(repo, "go.mod")
	committed, err := os.ReadFile(modPath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(committed), "go 1.22") {
		t.Fatalf("go.mod has no go line:\n%s", committed)
	}
	write(t, modPath, strings.Replace(string(committed), "go 1.22", "go 1.23", 1))
	stdout, stderr = plan(config, "HEAD", "hit")
	some(stdout, stderr, "go line")
	write(t, modPath, string(committed))

	bump := func(pairs ...string) {
		t.Helper()
		text, err := os.ReadFile(filepath.Join(repo, "go.mod"))
		if err != nil {
			t.Fatal(err)
		}
		s := string(text)
		for i := 0; i < len(pairs); i += 2 {
			if !strings.Contains(s, pairs[i]) {
				t.Fatalf("go.mod has no %s\n%s", pairs[i], s)
			}
			s = strings.ReplaceAll(s, pairs[i], pairs[i+1])
		}
		write(t, filepath.Join(repo, "go.mod"), s)
		tidy()
		git("add", ".")
		git("commit", "-q", "-m", "bump")
	}
	bump("example.com/onlytest v0.1.0", "example.com/onlytest v0.2.0", "example.com/helponly v0.1.0", "example.com/helponly v0.2.0")
	stdout, stderr = plan(config, "HEAD^1", "hit")
	none(stdout, stderr, "test-only deps")
	if !strings.Contains(stderr, "plan-reason: non-test build list unchanged") {
		t.Fatalf("test-only deps: stderr %s", stderr)
	}

	bump("example.com/prod v0.1.0", "example.com/prod v0.2.0")
	stdout, stderr = plan(config, "HEAD^1", "hit")
	some(stdout, stderr, "prod bump")
	if !strings.Contains(stderr, "changed") || strings.Contains(stderr, "plan-reason:") {
		t.Fatalf("prod bump: stderr %s", stderr)
	}
	stdout, stderr = plan(config, "HEAD^1", "miss")
	none(stdout, stderr, "prod bump, go.sum does not name the library")
	if !strings.Contains(stderr, "plan-reason: no reference consumer depends on this module") {
		t.Fatalf("go.sum miss: stderr %s", stderr)
	}
	stdout, stderr = plan(config, "HEAD^1", "fail")
	some(stdout, stderr, "prod bump, go.sum unreadable")
}
