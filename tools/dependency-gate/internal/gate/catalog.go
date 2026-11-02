package gate

import (
	"fmt"
	"maps"
	"path"
	"slices"
)

// Level of a check, ordered by weight. The zero value means "not set".
type Level int8

const (
	Off Level = iota + 1
	Info
	Warn
	Fail
)

var levelNames = [...]string{Off: "off", Info: "info", Warn: "warn", Fail: "fail"}

func (l Level) String() string {
	if l < Off || l > Fail {
		return fmt.Sprintf("Level(%d)", l)
	}
	return levelNames[l]
}

func (l Level) MarshalText() ([]byte, error) {
	if l < Off || l > Fail {
		return nil, fmt.Errorf("invalid level %d", l)
	}
	return []byte(levelNames[l]), nil
}

func (l *Level) UnmarshalText(b []byte) error {
	i := slices.Index(levelNames[Off:], string(b))
	if i < 0 {
		return fmt.Errorf("unknown level %q", b)
	}
	*l = Off + Level(i)
	return nil
}

const (
	Maven = "maven"
	Go    = "go"
)

// Checks of the gate itself: they apply to any ecosystem and cannot be turned off or excepted.
const (
	ConfigInvalid     = "config-invalid"
	ExceptionInvalid  = "exception-invalid"
	ExceptionExpired  = "exception-expired"
	ExceptionExpiring = "exception-expiring"
	ExceptionUnused   = "exception-unused"
	ToolError         = "tool-error"
)

type checkDef struct {
	// default level per ecosystem; the "" key marks a gate check
	levels map[string]Level
	// applies only to pull requests into (and pushes to) protected branches
	protectedOnly bool
}

func maven(l Level) map[string]Level     { return map[string]Level{Maven: l} }
func golang(l Level) map[string]Level    { return map[string]Level{Go: l} }
func both(m, g Level) map[string]Level   { return map[string]Level{Maven: m, Go: g} }
func gateCheck(l Level) map[string]Level { return map[string]Level{"": l} }

var catalog = map[string]checkDef{
	"pin-exceeded":     {levels: maven(Fail)},
	"pin-mismatch":     {levels: maven(Fail)},
	"pin-lagging":      {levels: maven(Warn)},
	"pin-conflict":     {levels: maven(Warn)},
	"shared-property":  {levels: maven(Warn)},
	"feature-snapshot": {levels: maven(Fail), protectedOnly: true},
	"snapshot":         {levels: maven(Off)},
	"api-exposed":      {levels: maven(Warn)},

	"apidiff-exposed":  {levels: golang(Fail)},
	"apidiff-internal": {levels: golang(Warn)},
	"build":            {levels: golang(Fail)},
	"vet-new":          {levels: golang(Fail)},
	"tests-compile":    {levels: golang(Fail)},
	"init-all":         {levels: golang(Fail)},
	"go-directive":     {levels: golang(Fail)},
	"mod-verify":       {levels: golang(Fail)},
	"mod-tidy":         {levels: golang(Warn)},
	"pseudo-version":   {levels: golang(Warn)},
	"replace-local":    {levels: golang(Warn)},

	"family-split":        {levels: both(Warn, Fail)},
	"consumer-build":      {levels: both(Off, Fail)},
	"consumer-test":       {levels: golang(Fail)},
	"consumer-test-flaky": {levels: golang(Info)},
	"downgrade":           {levels: both(Warn, Warn)},
	"dependency-diff":     {levels: both(Info, Info)},
	"vuln-new":            {levels: both(Warn, Warn)},
	"vuln-fixed":          {levels: both(Info, Info)},
	"vuln-open":           {levels: both(Info, Info)},
	"license-not-allowed": {levels: both(Warn, Warn)},
	"license-changed":     {levels: both(Warn, Warn)},
	"license-unknown":     {levels: both(Warn, Warn)},

	ConfigInvalid:     {levels: gateCheck(Fail)},
	ExceptionInvalid:  {levels: gateCheck(Fail)},
	ExceptionExpired:  {levels: gateCheck(Fail)},
	ExceptionExpiring: {levels: gateCheck(Warn)},
	ExceptionUnused:   {levels: gateCheck(Warn)},
	ToolError:         {levels: gateCheck(Fail)},
}

// Adapters whose failure is a warning: network outages there must not stop every pull request.
var softAdapters = []string{"osv", "go-consumers"}

const expiringDays = 14

func knownCheck(id string) bool {
	_, ok := catalog[id]
	return ok
}

func isGateCheck(id string) bool {
	_, ok := catalog[id].levels[""]
	return ok
}

// appliesTo reports whether a check exists in an ecosystem ("" for gate findings).
func appliesTo(id, ecosystem string) bool {
	levels := catalog[id].levels
	if _, ok := levels[""]; ok {
		return true
	}
	_, ok := levels[ecosystem]
	return ok
}

func defaultLevel(id, ecosystem, adapter string) Level {
	if id == ToolError && slices.Contains(softAdapters, adapter) {
		return Warn
	}
	levels := catalog[id].levels
	if l, ok := levels[""]; ok {
		return l
	}
	return levels[ecosystem]
}

// matchChecks expands a check pattern (id or glob) to the adapter checks it covers.
func matchChecks(pattern string) []string {
	var ids []string
	for _, id := range slices.Sorted(maps.Keys(catalog)) {
		if ok, _ := path.Match(pattern, id); ok && !isGateCheck(id) {
			ids = append(ids, id)
		}
	}
	return ids
}
