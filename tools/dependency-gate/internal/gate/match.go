package gate

import (
	"errors"
	"fmt"
	"path"
	"regexp"
	"slices"
	"strings"
)

// Selector picks findings by their fields. Each value is exact, * (anything) or a /regex/ with an optional i flag.
type Selector struct {
	Package string `json:"package,omitempty"`
	Version string `json:"version,omitempty"`
	Symbol  string `json:"symbol,omitempty"`
	Vuln    string `json:"vuln,omitempty"`
	License string `json:"license,omitempty"`
	Path    string `json:"path,omitempty"`
}

// fields lists the selector values in the order of selectorFields.
func (s Selector) fields() [len(selectorFields)]string {
	return [...]string{s.Package, s.Version, s.Symbol, s.Vuln, s.License, s.Path}
}

var selectorFields = [...]string{"package", "version", "symbol", "vuln", "license", "path"}

func (s Selector) String() string {
	var parts []string
	for i, v := range s.fields() {
		if v != "" {
			parts = append(parts, selectorFields[i]+" "+v)
		}
	}
	return strings.Join(parts, ", ")
}

// matcher tests one field; nil matches anything.
type matcher func(string) bool

var regexLiteral = regexp.MustCompile(`(?s)^/(.*)/([a-z]*)$`)

func compileMatcher(value string) (matcher, error) {
	if value == "" || value == "*" {
		return nil, nil
	}
	m := regexLiteral.FindStringSubmatch(value)
	if m == nil {
		return func(s string) bool { return s == value }, nil
	}
	expr := m[1]
	switch m[2] {
	case "":
	case "i":
		expr = "(?i)" + expr
	default:
		return nil, fmt.Errorf("unsupported regex flags %q in %s", m[2], value)
	}
	re, err := regexp.Compile(expr)
	if err != nil {
		return nil, fmt.Errorf("invalid regex %s: %w", value, err)
	}
	return re.MatchString, nil
}

// exact reports whether a selector value matches one value only.
func exact(value string) bool {
	return value != "" && value != "*" && !regexLiteral.MatchString(value)
}

// selector holds a matcher per selector field; the last one, path, tests the location files.
type selector [len(selectorFields)]matcher

func (s Selector) compile() (selector, error) {
	var c selector
	var errs []error
	for i, v := range s.fields() {
		var err error
		c[i], err = compileMatcher(v)
		errs = append(errs, err)
	}
	return c, errors.Join(errs...)
}

func (s selector) match(f *Finding) bool {
	for i, v := range [...]string{f.Package, f.Version, f.Symbol, f.Vuln, f.License} {
		if s[i] != nil && !s[i](v) {
			return false
		}
	}
	inFile := s[len(s)-1]
	return inFile == nil || slices.ContainsFunc(f.Locations, func(l Location) bool { return inFile(l.File) })
}

// rule is a compiled exception or ignore entry.
type rule struct {
	selector
	// check patterns of an exception; empty for an ignore rule, which belongs to one check
	checks []string
	ref    ExceptionRef
	// where the entry is written, for the report
	at   []Location
	what string
}

func (r *rule) matches(f *Finding) bool {
	if len(r.checks) > 0 && !slices.ContainsFunc(r.checks, func(p string) bool {
		ok, _ := path.Match(p, f.Check)
		return ok
	}) {
		return false
	}
	return r.selector.match(f)
}

// covers reports whether the exception applies to one of the checks.
func (r *rule) covers(ran map[string]bool) bool {
	return slices.ContainsFunc(r.checks, func(p string) bool {
		return slices.ContainsFunc(matchChecks(p), func(id string) bool { return ran[id] })
	})
}
