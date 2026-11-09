package render

import (
	"bytes"
	"fmt"
	"slices"
	"strings"
	"testing"

	"github.com/sethvargo/go-githubactions"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
)

func report() *gate.Report {
	return &gate.Report{
		Mode:    gate.ModeEnforce,
		Verdict: gate.VerdictFail,
		Adapters: []gate.Adapter{
			{Name: "maven-pins", Status: gate.AdapterOK},
			{Name: "go-library", Status: gate.AdapterSkipped, Reason: "go.mod unchanged"},
		},
		Findings: []*gate.Finding{
			{Level: gate.Fail, Status: gate.New, Item: gate.Item{Ecosystem: gate.Maven,
				Check: "pin-exceeded", Package: "com.rabbitmq:amqp-client", Version: "5.37.0", Message: "above the pin 5.30.0",
				Note: "a|b", Locations: []gate.Location{{File: "bom/pom.xml", Line: 10}, {File: "lib/pom.xml"}}}},
			{Level: gate.Warn, Status: gate.Preexisting, Item: gate.Item{Ecosystem: gate.Maven,
				Check: "pin-exceeded", Package: "org.projectlombok:lombok", Version: "1.18.48", Message: "above the pin", Transitive: true,
				Locations: []gate.Location{{File: "lib/pom.xml", Line: 27}}}},
			{Level: gate.Fail, Status: gate.Allowed, Exception: &gate.ExceptionRef{
				Source: ".github/dependency-gate.json#/exceptions/5", Reason: "coordinated", Until: "2026-11-05"},
				Item: gate.Item{Ecosystem: gate.Go, Check: "apidiff-exposed", Package: "github.com/opensearch-project/opensearch-go/v4", Message: "incompatible",
					Locations: []gate.Location{{File: "go.mod", Line: 7}}}},
			{Level: gate.Warn, Status: gate.New, Item: gate.Item{
				Check: gate.ExceptionUnused, Package: ".github/dependency-gate.json#/exceptions/1", Message: "matches nothing"}},
		},
		Counts: gate.Counts{Fail: 1, Warn: 1, Info: 1, Preexisting: 1, Allowed: 1},
	}
}

func TestSummary(t *testing.T) {
	for _, tc := range []struct {
		name   string
		change func(*gate.Report)
		title  string
		want   []string
	}{
		{"enforce", func(*gate.Report) {}, "❌ Dependency gate: failed", []string{
			"1 fail, 1 warn, 1 already on the base branch, 1 allowed",
			"`maven-pins` ok · `go-library` skipped (go.mod unchanged)",
			"| ❌ | pin-exceeded | `com.rabbitmq:amqp-client` | `5.37.0` | above the pin 5.30.0 — a\\|b | `bom/pom.xml:10` +1 |",
			"above the pin (transitive) (already on the base branch)",
			"| ☑️ | apidiff-exposed |",
			"(allowed: coordinated, until 2026-11-05, .github/dependency-gate.json#/exceptions/5)",
			"#### Gate\n", "#### Maven\n", "#### Go\n",
		}},
		{"pilot", func(r *gate.Report) { r.Mode = gate.ModeReport }, "❌ Dependency gate (pilot): would fail", []string{"> Pilot"}},
		{"pass", func(r *gate.Report) { r.Mode, r.Verdict, r.Findings = gate.ModeReport, gate.VerdictPass, nil },
			"✅ Dependency gate: passed", nil},
		{"consumer matrix", func(r *gate.Report) {
			r.Adapters = nil
			for range 28 {
				r.Adapters = append(r.Adapters, gate.Adapter{Name: "go-consumers", Status: gate.AdapterOK})
			}
			r.Adapters = append(r.Adapters,
				gate.Adapter{Name: "go-consumers", Status: gate.AdapterError, Reason: "a/b: the job failed"},
				gate.Adapter{Name: "maven-consumers", Status: gate.AdapterSkipped, Reason: "job skipped"})
		}, "❌ Dependency gate: failed", []string{
			"`go-consumers` 28 ok · `go-consumers` error (a/b: the job failed) · `maven-consumers` skipped (job skipped)",
		}},
		{"scope", func(r *gate.Report) {
			r.Scope = map[string]gate.Scope{
				gate.Maven: {Roots: []string{"core"}, Exclude: []string{"^a/", "^b|c/"}, Transitive: gate.Warn},
				gate.Go:    {Modules: []string{".", "tools"}, Transitive: gate.Off},
			}
		}, "❌ Dependency gate: failed", []string{
			"\n\nScope: go: only modules `.`, `tools`; transitive findings dropped · " +
				"maven: only roots `core`; not checked `^a/`, `^b\\|c/`; transitive findings at most warn\n",
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := report()
			tc.change(r)
			md := Summary(r)
			if !strings.HasPrefix(md, "### "+tc.title+"\n") {
				t.Errorf("want the title %q:\n%s", tc.title, md)
			}
			for _, want := range tc.want {
				if !strings.Contains(md, want) {
					t.Errorf("summary lacks %q:\n%s", want, md)
				}
			}
			if tc.name == "enforce" {
				var at []int
				for _, s := range []string{"#### Gate\n", "#### Maven\n", "#### Go\n"} {
					at = append(at, strings.Index(md, s))
				}
				if slices.Contains(at, -1) || !slices.IsSorted(at) {
					t.Errorf("sections at %v", at)
				}
			}
		})
	}
}

func TestComment(t *testing.T) {
	r := report()
	for i := range 25 {
		r.Findings = append(r.Findings, &gate.Finding{Level: gate.Warn, Status: gate.New,
			Item: gate.Item{Ecosystem: gate.Go, Check: "pin-lagging", Package: fmt.Sprint("m", i), Message: "untidy"}})
	}
	md := Comment(r, "https://github.com/o/r/actions/runs/1")
	for _, want := range []string{Marker + "\n### ❌ Dependency gate: failed", "… and 7 more.", "[job summary](https://github.com/o/r/actions/runs/1)"} {
		if !strings.Contains(md, want) {
			t.Errorf("comment lacks %q:\n%s", want, md)
		}
	}
	// the preexisting, allowed and info findings are not listed; 2 + 25 others, 20 shown
	if n := strings.Count(md, "\n| ❌ |") + strings.Count(md, "\n| ⚠️ |"); n != CommentLimit {
		t.Errorf("%d rows", n)
	}
	for _, summaryOnly := range []string{"apidiff-exposed", "lombok"} {
		if strings.Contains(md, summaryOnly) {
			t.Errorf("%s belongs to the summary only", summaryOnly)
		}
	}
}

func TestConsumerLine(t *testing.T) {
	r := report()
	r.Mode = gate.ModeReport
	r.Counts.Fail = 12
	line := ConsumerLine(r, "https://github.com/o/r/actions/runs/1/job/2")
	if line != "**Consumers:** would fail (12 fail). [job summary](https://github.com/o/r/actions/runs/1/job/2)" {
		t.Errorf("line %q", line)
	}
	r.Mode = gate.ModeEnforce
	if got := ConsumerLine(r, ""); got != "**Consumers:** failed (12 fail)." {
		t.Errorf("enforce line %q", got)
	}
	r.Verdict = gate.VerdictPass
	if ConsumerLine(r, "https://example") != "" {
		t.Error("a pass has no consumers line")
	}
	pin := Comment(report(), "https://github.com/o/r/actions/runs/1")
	with := JoinComment(pin, line)
	gotPin, gotNote := SplitComment(with)
	if gotPin != strings.TrimRight(pin, "\n") || gotNote != line {
		t.Errorf("split pin %q note %q", gotPin, gotNote)
	}
	if back := JoinComment(gotPin, ""); back != strings.TrimRight(pin, "\n")+"\n" || strings.Contains(back, ConsumerMarker) {
		t.Errorf("clearing the note: %q", back)
	}
	only := JoinComment("", line)
	if !strings.HasPrefix(only, Marker+"\n\n"+ConsumerMarker+"\n") {
		t.Errorf("note without a pin part: %q", only)
	}
	if JoinComment(Marker, "") != "" {
		t.Error("a marker alone is not a comment")
	}
}

func TestNeedsComment(t *testing.T) {
	r := report()
	if !NeedsComment(r) {
		t.Error("a new failure needs a comment")
	}
	// what the base branch already has, what is allowed or only informs does not
	r.Findings = slices.DeleteFunc(r.Findings, func(f *gate.Finding) bool { return f.Status == gate.New && f.Level >= gate.Warn })
	if NeedsComment(r) {
		t.Errorf("no new warn or fail findings: %+v", r.Findings)
	}
}

func TestAnnotations(t *testing.T) {
	var out bytes.Buffer
	Annotate(githubactions.New(githubactions.WithWriter(&out)), report())
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	// the new failure and the preexisting warning; the allowed finding and those without a line are left out
	want := []string{
		"::error file=bom/pom.xml,line=10,title=dependency-gate/pin-exceeded::com.rabbitmq:amqp-client:5.37.0 - above the pin 5.30.0",
		"::warning file=lib/pom.xml,line=27,",
	}
	if len(lines) != len(want) {
		t.Fatalf("got:\n%s", out.String())
	}
	for i, prefix := range want {
		if !strings.HasPrefix(lines[i], prefix) {
			t.Errorf("line %d: %s", i, lines[i])
		}
	}
}
