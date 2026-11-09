// Package render turns a gate report into the job summary, the sticky PR comment and annotations.
package render

import (
	"fmt"
	"maps"
	"slices"
	"strings"
	"text/template"

	"github.com/sethvargo/go-githubactions"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
)

// Marker identifies the sticky comment of the gate.
const Marker = "<!-- dependency-gate -->"

// ConsumerMarker starts the one line about Consumers. The pin check and the consumers check
// rewrite only their own part, so whichever finishes last keeps the other.
const ConsumerMarker = "<!-- dependency-gate-consumers -->"

// CommentLimit is how many findings the PR comment lists; the summary has them all.
const CommentLimit = 20

type section struct {
	Title    string
	Findings []*gate.Finding
}

type view struct {
	*gate.Report
	Sections []section
	// comment only
	Top    []*gate.Finding
	More   int
	RunURL string
}

var funcs = template.FuncMap{
	"title":    title,
	"icon":     icon,
	"problem":  problem,
	"where":    where,
	"code":     code,
	"cell":     cell,
	"scope":    scope,
	"adapters": adapters,
}

var templates = template.Must(template.New("").Funcs(funcs).Parse(`
{{- define "head" -}}
### {{title .Report}}
{{if eq .Mode "report"}}
> Pilot (` + "`mode: report`" + `): the check stays green; this is what ` + "`enforce`" + ` would decide.
{{end}}
{{with .Counts}}{{.Fail}} fail, {{.Warn}} warn, {{.Preexisting}} already on the base branch, {{.Allowed}} allowed{{end}}
{{- end}}

{{- define "table" -}}
| | Check | Package | Version | Problem | Where |
|---|---|---|---|---|---|
{{range .}}| {{icon .}} | {{.Check}} | {{code .Package}} | {{code .Version}} | {{problem .}} | {{where .}} |
{{end}}
{{- end}}

{{- define "summary" -}}
{{template "head" .}}

Adapters: {{adapters .Adapters}}
{{- with .Scope}}

Scope: {{scope .}}
{{- end}}
{{range .Sections}}
#### {{.Title}}

{{template "table" .Findings}}
{{end}}
{{- end}}

{{- define "comment" -}}
` + Marker + `
{{template "head" .}}
{{if .Top}}
{{template "table" .Top}}
{{- if .More}}
… and {{.More}} more.
{{end}}
{{end}}
{{with .RunURL}}Details: [job summary]({{.}}){{end}}
{{end}}
`))

// Summary is the job summary: every finding by ecosystem.
func Summary(r *gate.Report) string {
	var gateFindings []*gate.Finding
	byEco := map[string][]*gate.Finding{}
	for _, f := range r.Findings {
		if f.Ecosystem == "" {
			gateFindings = append(gateFindings, f)
		} else {
			byEco[f.Ecosystem] = append(byEco[f.Ecosystem], f)
		}
	}
	var sections []section
	for _, s := range []section{
		{"Gate", gateFindings}, {"Maven", byEco[gate.Maven]}, {"Go", byEco[gate.Go]},
	} {
		if len(s.Findings) > 0 {
			sections = append(sections, s)
		}
	}
	return execute("summary", view{Report: r, Sections: sections})
}

// Comment is the sticky PR comment: the findings this change brings, from warn up, and a link to the run.
// Findings already on the base branch are in the job summary only.
func Comment(r *gate.Report, runURL string) string {
	var top []*gate.Finding
	for _, f := range r.Findings {
		if brought(f) {
			top = append(top, f)
		}
	}
	v := view{Report: r, RunURL: runURL, Top: top}
	if len(top) > CommentLimit {
		v.Top, v.More = top[:CommentLimit], len(top)-CommentLimit
	}
	return execute("comment", v)
}

// NeedsComment reports whether the change brings new warn or fail findings: only then is a comment created,
// an existing one is always updated.
func NeedsComment(r *gate.Report) bool {
	return slices.ContainsFunc(r.Findings, brought)
}

// SplitComment separates the pin part of a sticky comment from the consumers line.
func SplitComment(body string) (pin, note string) {
	pin, note, found := strings.Cut(body, ConsumerMarker)
	if !found {
		return strings.TrimRight(body, "\n"), ""
	}
	return strings.TrimRight(pin, "\n"), strings.TrimSpace(note)
}

// JoinComment builds the sticky comment. An empty note drops the consumers line.
// A comment that would be only the marker is empty: there is nothing to post.
func JoinComment(pin, note string) string {
	pin = strings.TrimRight(pin, "\n")
	note = strings.TrimSpace(note)
	if note == "" {
		if strings.TrimSpace(pin) == "" || strings.TrimSpace(pin) == Marker {
			return ""
		}
		return pin + "\n"
	}
	if strings.TrimSpace(pin) == "" {
		pin = Marker
	}
	return pin + "\n\n" + ConsumerMarker + "\n" + note + "\n"
}

// ConsumerLine is the consumers line of the sticky comment: the fail fact and a link to the job
// summary. A pass or a warning leaves it empty, so a later run drops the line.
func ConsumerLine(r *gate.Report, detailsURL string) string {
	if r == nil || r.Verdict != gate.VerdictFail {
		return ""
	}
	verb := "failed"
	if r.Mode == gate.ModeReport {
		verb = "would fail"
	}
	line := "**Consumers:** " + verb
	if n := r.Counts.Fail; n > 0 {
		line += fmt.Sprintf(" (%d fail)", n)
	}
	line += "."
	if detailsURL != "" {
		line += " [job summary](" + detailsURL + ")"
	}
	return line
}

// brought reports a warn or fail finding that this change introduces.
func brought(f *gate.Finding) bool {
	return f.Status == gate.New && f.Level >= gate.Warn
}

func execute(name string, v view) string {
	var b strings.Builder
	if err := templates.ExecuteTemplate(&b, name, v); err != nil {
		panic(err) // templates are static: an error is a bug
	}
	return b.String()
}

// Annotate writes workflow annotations for findings located in a repository file and line; failures first.
func Annotate(a *githubactions.Action, r *gate.Report) {
	for _, f := range r.Findings {
		if f.Status == gate.Allowed || f.Level < gate.Warn {
			continue
		}
		i := slices.IndexFunc(f.Locations, func(l gate.Location) bool { return l.Line > 0 })
		if i < 0 {
			continue
		}
		loc := f.Locations[i]
		msg := strings.TrimPrefix(fmt.Sprintf("%s:%s - %s", f.Package, f.Version, f.Message), ":")
		if f.Note != "" {
			msg += " (" + f.Note + ")"
		}
		out := a.WithFieldsMap(map[string]string{
			"file": loc.File, "line": fmt.Sprint(loc.Line), "title": "dependency-gate/" + f.Check,
		})
		if f.Status == gate.New && f.Level == gate.Fail {
			out.Errorf("%s", msg)
		} else {
			out.Warningf("%s", msg)
		}
	}
}

func title(r *gate.Report) string {
	pilot := r.Mode == gate.ModeReport
	switch r.Verdict {
	case gate.VerdictFail:
		if pilot {
			return "❌ Dependency gate (pilot): would fail"
		}
		return "❌ Dependency gate: failed"
	case gate.VerdictWarn:
		if pilot {
			return "⚠️ Dependency gate (pilot): would warn"
		}
		return "⚠️ Dependency gate: passed with warnings"
	default:
		return "✅ Dependency gate: passed"
	}
}

func icon(f *gate.Finding) string {
	switch {
	case f.Status == gate.Allowed:
		return "☑️"
	case f.Level == gate.Fail:
		return "❌"
	case f.Level == gate.Warn:
		return "⚠️"
	default:
		return "ℹ️"
	}
}

func problem(f *gate.Finding) string {
	text := f.Message
	if f.From != "" {
		text = "from " + f.From + ": " + text
	}
	if f.Note != "" {
		text += " — " + f.Note
	}
	if f.Transitive {
		text += " (transitive)"
	}
	switch f.Status {
	case gate.Preexisting:
		text += " (already on the base branch)"
	case gate.Allowed:
		e := f.Exception
		text += " (allowed: " + e.Reason
		if e.Until != "" {
			text += ", until " + e.Until
		}
		text += ", " + e.Source + ")"
	}
	return cell(text)
}

func where(f *gate.Finding) string {
	if len(f.Locations) == 0 {
		return ""
	}
	l := f.Locations[0]
	text := l.File
	if l.Line > 0 {
		text += fmt.Sprintf(":%d", l.Line)
	}
	text = code(text)
	if n := len(f.Locations) - 1; n > 0 {
		text += fmt.Sprintf(" +%d", n)
	}
	return text
}

// scope tells what the configuration keeps from the adapters, locked checks included.
func scope(s map[string]gate.Scope) string {
	var parts []string
	for _, eco := range slices.Sorted(maps.Keys(s)) {
		var what []string
		for _, list := range []struct {
			label string
			items []string
		}{{"only roots", s[eco].Roots}, {"only modules", s[eco].Modules}, {"not checked", s[eco].Exclude}} {
			if len(list.items) == 0 {
				continue
			}
			quoted := make([]string, len(list.items))
			for i, e := range list.items {
				quoted[i] = code(e)
			}
			what = append(what, list.label+" "+strings.Join(quoted, ", "))
		}
		switch t := s[eco].Transitive; t {
		case 0:
		case gate.Off:
			what = append(what, "transitive findings dropped")
		default:
			what = append(what, "transitive findings at most "+t.String())
		}
		parts = append(parts, eco+": "+strings.Join(what, "; "))
	}
	return strings.Join(parts, " · ")
}

// adapters collapses repeated runs of one adapter. Each consumer writes its own findings file, so a lib-go
// matrix is dozens of identical go-consumers entries; the summary counts them.
func adapters(list []gate.Adapter) string {
	if len(list) == 0 {
		return "none"
	}
	type key struct{ name, status, reason string }
	var order []key
	count := map[key]int{}
	for _, a := range list {
		k := key{a.Name, string(a.Status), a.Reason}
		if count[k] == 0 {
			order = append(order, k)
		}
		count[k]++
	}
	var parts []string
	for _, k := range order {
		text := code(k.name) + " "
		if n := count[k]; n > 1 {
			text += fmt.Sprintf("%d ", n)
		}
		text += k.status
		if k.reason != "" {
			text += " (" + cell(k.reason) + ")"
		}
		parts = append(parts, text)
	}
	return strings.Join(parts, " · ")
}

func code(s string) string {
	if s == "" {
		return ""
	}
	return "`" + strings.ReplaceAll(cell(s), "`", "'") + "`"
}

// cell keeps text inside one markdown table cell.
func cell(s string) string {
	return strings.NewReplacer("|", `\|`, "\r", "", "\n", "<br>").Replace(s)
}
