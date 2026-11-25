package gate

import (
	"cmp"
	"fmt"
	"log/slog"
	"maps"
	"slices"
	"strings"
)

type Status string

const (
	New         Status = "new"
	Preexisting Status = "preexisting"
	Allowed     Status = "allowed"
)

type Verdict string

const (
	VerdictPass Verdict = "pass"
	VerdictWarn Verdict = "warn"
	VerdictFail Verdict = "fail"
)

// Finding is an adapter or gate finding after policy.
type Finding struct {
	Key     string `json:"key"`
	Adapter string `json:"adapter,omitempty"`
	Level   Level  `json:"level"`
	Status  Status `json:"status"`
	Item
	Exception *ExceptionRef `json:"exception,omitempty"`
}

// ExceptionRef is the exception or ignore rule that allowed a finding.
type ExceptionRef struct {
	Source string `json:"source"`
	Reason string `json:"reason"`
	Until  string `json:"until,omitempty"`
	Issue  string `json:"issue,omitempty"`
}

type Counts struct {
	Fail        int `json:"fail"`
	Warn        int `json:"warn"`
	Info        int `json:"info"`
	Preexisting int `json:"preexisting"`
	Allowed     int `json:"allowed"`
}

// Report is report.v1.
type Report struct {
	Schema      string           `json:"schema"`
	Repository  string           `json:"repository,omitempty"`
	PullRequest int              `json:"pullRequest,omitempty"`
	Base        string           `json:"base,omitempty"`
	Head        string           `json:"head,omitempty"`
	Gate        string           `json:"gate"`
	Config      []string         `json:"config"`
	Scope       map[string]Scope `json:"scope,omitempty"`
	// DryRun skips the failure. The verdict is unchanged.
	DryRun   bool       `json:"dryRun,omitempty"`
	Verdict  Verdict    `json:"verdict"`
	Adapters []Adapter  `json:"adapters"`
	Findings []*Finding `json:"findings"`
	Counts   Counts     `json:"counts"`
}

// Input of one evaluation.
type Input struct {
	Head, Base []*FindingsFile
	// tool errors found while reading the findings
	Problems []*Finding
	// GitHub job result by adapter name: adapters that must have reported
	Expected map[string]string
	// target branch of the pull request, or the pushed branch
	Branch string
	Labels []string
}

// Evaluate applies levels, ignore rules, exceptions and the baseline, and computes the verdict.
func (p *Policy) Evaluate(in Input) *Report {
	r := &Report{Schema: ReportSchema, Config: p.Sources, Scope: p.Scope,
		Adapters: []Adapter{}, Findings: []*Finding{}}
	ran := map[string]bool{}
	var found []*Finding
	for _, f := range in.Head {
		a := f.Adapter
		r.Adapters = append(r.Adapters, a.Adapter)
		switch a.Status {
		case AdapterError:
			found = append(found, toolError(a.Name, cmp.Or(a.Reason, "the adapter failed")))
		case AdapterOK:
			for _, id := range a.Checks {
				ran[id] = true
			}
			for _, item := range f.Findings {
				item.Ecosystem = cmp.Or(item.Ecosystem, a.Ecosystem)
				if problem := unknown(item); problem != "" {
					found = append(found, toolError(a.Name, problem))
					continue
				}
				found = append(found, &Finding{Adapter: a.Name, Item: item})
			}
		}
	}
	for _, name := range slices.Sorted(maps.Keys(in.Expected)) {
		if slices.ContainsFunc(r.Adapters, func(a Adapter) bool { return a.Name == name }) {
			continue
		}
		a, problem := missing(name, in.Expected[name])
		r.Adapters = append(r.Adapters, a)
		if problem != nil {
			found = append(found, problem)
		}
	}

	baseline := map[string]bool{}
	for _, f := range in.Base {
		if f.Adapter.Status != AdapterOK {
			slog.Warn("no baseline: every finding counts as new", "adapter", f.Adapter.Name, "status", f.Adapter.Status)
			continue
		}
		for _, item := range f.Findings {
			item.Ecosystem = cmp.Or(item.Ecosystem, f.Adapter.Ecosystem)
			baseline[key(item)] = true
		}
	}

	used := map[*rule]bool{}
	for _, f := range found {
		f.Level = p.Level(f.Check, f.Ecosystem, f.Adapter, in.Labels)
		rule := p.allowedBy(f)
		if rule != nil {
			used[rule] = true
		}
		// an exception for a finding that is off here is still in use
		if f.Level == Off {
			continue
		}
		f.Key = key(f.Item)
		f.Status = New
		if rule != nil {
			f.Status = Allowed
			f.Exception = &rule.ref
		} else if baseline[f.Key] {
			f.Status = Preexisting
			f.Level = min(f.Level, Warn)
		}
		r.Findings = append(r.Findings, f)
	}

	// gate findings are evaluated last: unused exceptions are known only now
	var gate []*Finding
	for _, f := range slices.Concat(in.Problems, p.Problems) {
		gate = append(gate, &Finding{Adapter: f.Adapter, Item: f.Item})
	}
	for _, e := range p.exceptions {
		if !used[e] && e.covers(ran) {
			gate = append(gate, &Finding{Item: Item{Check: ExceptionUnused, Package: e.ref.Source, Locations: e.at,
				Message: fmt.Sprintf("exception for %s matches nothing, remove it (reason: %s)", e.what, e.ref.Reason)}})
		}
	}
	for _, f := range gate {
		f.Level = p.Level(f.Check, "", f.Adapter, in.Labels)
		f.Key = key(f.Item)
		f.Status = New
		r.Findings = append(r.Findings, f)
	}

	slices.SortStableFunc(r.Findings, compareFindings)
	r.Counts, r.Verdict = tally(r.Findings)
	return r
}

// key identifies a finding across revisions: without locations, with the version, so a worse violation is new.
func key(i Item) string {
	return strings.Join([]string{i.Ecosystem, i.Check, i.Package, i.Version, i.Symbol}, "|")
}

// unknown explains why an adapter finding breaks the contract, or returns "".
func unknown(i Item) string {
	switch {
	case i.Ecosystem == "":
		return fmt.Sprintf("finding %q has no ecosystem", i.Check)
	case !knownCheck(i.Check) || isGateCheck(i.Check) || !appliesTo(i.Check, i.Ecosystem):
		return fmt.Sprintf("unknown %s check %q", i.Ecosystem, i.Check)
	}
	return ""
}

// allowedBy returns the ignore rule or the exception that allows f.
func (p *Policy) allowedBy(f *Finding) *rule {
	if isGateCheck(f.Check) {
		return nil
	}
	rules := slices.Concat(p.ignores[f.Check], p.ignores[f.Check+"|"+f.Ecosystem], p.exceptions)
	if i := slices.IndexFunc(rules, func(r *rule) bool { return r.matches(f) }); i >= 0 {
		return rules[i]
	}
	return nil
}

// missing describes an expected adapter that wrote no findings, from the result of its job.
// A skipped job may name the reason after ": " (skipped: non-test build list unchanged).
func missing(name, result string) (Adapter, *Finding) {
	status, reason, named := strings.Cut(result, ": ")
	a := Adapter{Name: name, Status: AdapterError, Reason: "job " + status}
	switch status {
	case "skipped":
		a.Status = AdapterSkipped
		if named {
			a.Reason = reason
		}
		return a, nil
	case "success":
		a.Reason = "the job succeeded without findings"
	}
	return a, toolError(name, a.Reason)
}

var statusOrder = []Status{New, Preexisting, Allowed}

func compareFindings(a, b *Finding) int {
	return cmp.Or(
		cmp.Compare(b.Level, a.Level),
		cmp.Compare(slices.Index(statusOrder, a.Status), slices.Index(statusOrder, b.Status)),
		cmp.Compare(a.Ecosystem, b.Ecosystem),
		cmp.Compare(a.Check, b.Check),
		cmp.Compare(a.Package, b.Package),
		cmp.Compare(a.Version, b.Version),
		cmp.Compare(a.Symbol, b.Symbol),
	)
}

func tally(findings []*Finding) (Counts, Verdict) {
	var c Counts
	verdict := VerdictPass
	for _, f := range findings {
		switch f.Status {
		case Allowed:
			c.Allowed++
			continue
		case Preexisting:
			c.Preexisting++
		case New:
			switch f.Level {
			case Fail:
				c.Fail++
			case Warn:
				c.Warn++
			case Info:
				c.Info++
			}
		}
		switch {
		case f.Level == Fail:
			verdict = VerdictFail
		case f.Level == Warn && verdict == VerdictPass:
			verdict = VerdictWarn
		}
	}
	return c, verdict
}

// Failed reports whether the check fails. A new fail does, unless this run is a dry run.
func (r *Report) Failed() bool {
	return r.Verdict == VerdictFail && !r.DryRun
}
