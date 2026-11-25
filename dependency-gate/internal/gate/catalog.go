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

const expiringDays = 14

type checkDef struct {
	// default level per ecosystem; the "" key marks a gate check
	levels map[string]Level
}

func golang(l Level) map[string]Level    { return map[string]Level{Go: l} }
func both(m, g Level) map[string]Level   { return map[string]Level{Maven: m, Go: g} }
func gateCheck(l Level) map[string]Level { return map[string]Level{"": l} }

var catalog = map[string]checkDef{
	"apidiff-exposed": {levels: golang(Fail)},

	"family-split":        {levels: both(Off, Fail)},
	"consumer-build":      {levels: both(Fail, Fail)},
	"consumer-test":       {levels: both(Fail, Fail)},
	"consumer-test-flaky": {levels: golang(Info)},

	ConfigInvalid: {levels: gateCheck(Fail)},
	ToolError:     {levels: gateCheck(Fail)},
}

// Adapters whose failure is a warning: a network failure of the consumer matrix must not stop every pull request.
var softAdapters = []string{"go-consumers", "maven-consumers"}

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
