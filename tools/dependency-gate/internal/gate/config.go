package gate

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"maps"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

// RepoConfigFile is the optional configuration of the checked repository.
const RepoConfigFile = ".github/dependency-gate.json"

type Mode string

const (
	ModeReport  Mode = "report"
	ModeEnforce Mode = "enforce"
)

// Config is config.v1: the infra defaults, a repository file, or both merged.
type Config struct {
	Schema            string                   `json:"$schema,omitempty"`
	Version           int                      `json:"version"`
	Mode              Mode                     `json:"mode,omitempty"`
	ProtectedBranches string                   `json:"protectedBranches,omitempty"`
	MaxExceptionDays  int                      `json:"maxExceptionDays,omitempty"`
	Locked            []string                 `json:"locked,omitempty"`
	Labels            Labels                   `json:"labels,omitzero"`
	Ecosystems        map[string]Ecosystem     `json:"ecosystems,omitempty"`
	Checks            map[string]CheckSettings `json:"checks,omitempty"`
	Families          []Family                 `json:"families,omitempty"`
	// adapter input the gate does not interpret; a repository may only set false
	Consumers  json.RawMessage `json:"consumers,omitempty"`
	Exceptions []Exception     `json:"exceptions,omitempty"`
}

type Labels struct {
	Levels map[string]map[string]Level `json:"levels,omitempty"`
}

// Ecosystem holds check settings and adapter settings (roots, modules, pins, ...), passed through as is.
type Ecosystem struct {
	Checks   map[string]CheckSettings
	Settings map[string]json.RawMessage
}

func (e Ecosystem) MarshalJSON() ([]byte, error) {
	m := make(map[string]any, len(e.Settings)+1)
	for k, v := range e.Settings {
		m[k] = v
	}
	if len(e.Checks) > 0 {
		m["checks"] = e.Checks
	}
	return json.Marshal(m)
}

func (e *Ecosystem) UnmarshalJSON(data []byte) error {
	if err := json.Unmarshal(data, &e.Settings); err != nil {
		return err
	}
	if raw, ok := e.Settings["checks"]; ok {
		delete(e.Settings, "checks")
		return json.Unmarshal(raw, &e.Checks)
	}
	return nil
}

// Setting decodes one adapter setting, e.g. the maven excludes.
func (e Ecosystem) Setting(key string, v any) error {
	if raw, ok := e.Settings[key]; ok {
		return json.Unmarshal(raw, v)
	}
	return nil
}

// CheckSettings is written either as a level or as an object.
type CheckSettings struct {
	Level          Level    `json:"level,omitzero"`
	Ignore         []Ignore `json:"ignore,omitempty"`
	FailOnSeverity string   `json:"failOnSeverity,omitempty"`
	Allow          []string `json:"allow,omitempty"`
}

func (c *CheckSettings) UnmarshalJSON(data []byte) error {
	if bytes.HasPrefix(bytes.TrimSpace(data), []byte(`"`)) {
		return json.Unmarshal(data, &c.Level)
	}
	type plain CheckSettings
	return json.Unmarshal(data, (*plain)(c))
}

type Family struct {
	Name      string `json:"name"`
	Ecosystem string `json:"ecosystem"`
	Match     string `json:"match"`
	Key       string `json:"key,omitempty"`
	Note      string `json:"note,omitempty"`
}

// Ignore is a permanent setting of a check: the findings it matches never count.
type Ignore struct {
	Selector
	Reason string `json:"reason"`
	origin origin
}

// Exception allows matching findings until a date.
type Exception struct {
	Selector
	Checks []string `json:"checks"`
	Reason string   `json:"reason"`
	Until  string   `json:"until"`
	Issue  string   `json:"issue,omitempty"`
	origin origin
	// schema violations of this entry
	invalid string
}

// origin is where a configuration entry is written.
type origin struct {
	file    string
	pointer string
	inRepo  bool
}

func (o origin) String() string {
	if o.pointer == "" {
		return o.file
	}
	return o.file + "#" + o.pointer
}

func (o origin) locations() []Location {
	if !o.inRepo {
		return nil
	}
	return []Location{{File: o.file}}
}

// Policy is the effective configuration with compiled rules.
type Policy struct {
	Config Config
	// configuration files in merge order
	Sources []string
	// findings of the gate about the configuration itself
	Problems []*Finding
	// what the configuration keeps from the adapters, by ecosystem
	Scope map[string]Scope

	protected  *regexp.Regexp
	exceptions []*rule
	// ignore rules by check id, or by "check|ecosystem" for ecosystem settings
	ignores map[string][]*rule
}

// Scope narrows what an ecosystem's adapters check, locked checks included; the report shows it.
type Scope struct {
	// configured Maven root poms or Go modules: nothing else is checked
	Roots   []string `json:"roots,omitempty"`
	Modules []string `json:"modules,omitempty"`
	// patterns of poms (maven excludes) or module directories (go exclude) not checked
	Exclude []string `json:"exclude,omitempty"`
	// highest level of findings only the resolved dependency tree shows
	Transitive Level `json:"transitive,omitzero"`
}

type LoadOptions struct {
	Schemas *Schemas
	// infra defaults and how the report names them
	Defaults     []byte
	DefaultsName string
	// root of the checked repository; RepoConfigFile under it is optional
	RepoDir string
	// git revision RepoConfigFile is read from; empty: the working tree
	RepoRev string
	Today   time.Time
}

// Load merges the repository file over the infra defaults. Problems of the repository file become findings;
// invalid defaults are an error of the gate itself.
func Load(o LoadOptions) (*Policy, error) {
	infra, errs, err := parse(o.Schemas, o.Defaults, origin{file: o.DefaultsName})
	if err != nil {
		return nil, fmt.Errorf("%s: %w", o.DefaultsName, err)
	}
	errs = append(errs, validate(&infra, &infra)...)
	for _, e := range infra.Exceptions {
		if e.invalid != "" {
			errs = append(errs, e.invalid)
		}
	}
	if len(errs) > 0 {
		return nil, fmt.Errorf("%s: %s", o.DefaultsName, strings.Join(errs, "; "))
	}
	p := &Policy{Sources: []string{o.DefaultsName}}
	cfg := infra
	if repo, ok := p.loadRepo(o, &infra); ok {
		source := RepoConfigFile
		if o.RepoRev != "" {
			source += "@" + o.RepoRev
		}
		p.Sources = append(p.Sources, source)
		cfg = merge(infra, repo)
	}
	cfg.Schema = ""
	p.Config = cfg
	if p.protected, err = regexp.Compile(cfg.ProtectedBranches); err != nil {
		return nil, err
	}
	if p.ignores, err = compileIgnores(cfg); err != nil {
		return nil, err
	}
	p.Scope = map[string]Scope{}
	for eco, e := range cfg.Ecosystems {
		var s Scope
		for key, v := range map[string]any{
			"roots": &s.Roots, "modules": &s.Modules, "excludes": &s.Exclude, "exclude": &s.Exclude, "transitive": &s.Transitive,
		} {
			if err := e.Setting(key, v); err != nil {
				return nil, err
			}
		}
		if s.Transitive != 0 || len(s.Roots)+len(s.Modules)+len(s.Exclude) > 0 {
			p.Scope[eco] = s
		}
	}
	for _, e := range cfg.Exceptions {
		p.addException(e, o.Today)
	}
	return p, nil
}

func (p *Policy) problem(check string, o origin, format string, args ...any) {
	p.Problems = append(p.Problems, &Finding{Item: Item{
		Check: check, Package: o.String(), Message: fmt.Sprintf(format, args...), Locations: o.locations(),
	}})
}

// loadRepo reads the repository file. An invalid exception is dropped alone; any other problem discards the
// whole file, so the gate fails closed on the infra defaults.
func (p *Policy) loadRepo(o LoadOptions, infra *Config) (Config, bool) {
	at := origin{file: RepoConfigFile, inRepo: true}
	var data []byte
	var err error
	if o.RepoRev != "" {
		data, err = readAt(o.RepoDir, o.RepoRev, RepoConfigFile)
	} else {
		data, err = os.ReadFile(filepath.Join(o.RepoDir, RepoConfigFile))
	}
	if errors.Is(err, fs.ErrNotExist) {
		return Config{}, false
	}
	if err != nil {
		p.problem(ConfigInvalid, at, "cannot read: %v", err)
		return Config{}, false
	}
	repo, errs, err := parse(o.Schemas, data, at)
	if err != nil {
		p.problem(ConfigInvalid, at, "not valid JSON: %v", err)
		return Config{}, false
	}
	errs = append(errs, validate(&repo, infra)...)
	errs = append(errs, restricted(&repo)...)
	if len(errs) > 0 {
		p.problem(ConfigInvalid, at, "the file is ignored until fixed: %s", strings.Join(errs, "; "))
		return Config{}, false
	}
	for _, e := range repo.Exceptions {
		if e.invalid != "" {
			p.problem(ExceptionInvalid, e.origin, "%s", e.invalid)
		}
	}
	repo.Exceptions = slices.DeleteFunc(repo.Exceptions, func(e Exception) bool { return e.invalid != "" })
	return repo, true
}

// readAt reads a file of the repository at a git revision; fs.ErrNotExist when the revision has no such file.
func readAt(dir, rev, name string) ([]byte, error) {
	git := func(args ...string) ([]byte, error) {
		out, err := exec.Command("git", slices.Concat([]string{"-C", dir}, args)...).Output()
		if ee, ok := errors.AsType[*exec.ExitError](err); ok {
			err = fmt.Errorf("git %s: %s", args[0], bytes.TrimSpace(ee.Stderr))
		}
		return out, err
	}
	listed, err := git("ls-tree", "--name-only", rev, "--", name)
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(listed)) == 0 {
		return nil, fs.ErrNotExist
	}
	return git("cat-file", "blob", rev+":"+name)
}

var exceptionPointer = regexp.MustCompile(`^/exceptions/(\d+)(/|$)`)

// parse validates data against config.v1 and decodes it. Schema violations inside an exception are attached to
// that exception; the others are returned.
func parse(s *Schemas, data []byte, o origin) (Config, []string, error) {
	violations, err := schemaViolations(s.config, data)
	if err != nil {
		return Config{}, nil, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		// a value of the wrong type is among the schema violations already
		if len(violations) == 0 {
			return Config{}, nil, err
		}
	}
	for i := range cfg.Exceptions {
		cfg.Exceptions[i].origin = origin{file: o.file, pointer: "/exceptions/" + strconv.Itoa(i), inRepo: o.inRepo}
	}
	for id, c := range cfg.Checks {
		tagIgnores(c.Ignore, o, "/checks/"+id)
	}
	for eco, e := range cfg.Ecosystems {
		for id, c := range e.Checks {
			tagIgnores(c.Ignore, o, "/ecosystems/"+eco+"/checks/"+id)
		}
	}
	var errs []string
	for _, v := range violations {
		if m := exceptionPointer.FindStringSubmatch(v.Pointer); m != nil {
			if i, _ := strconv.Atoi(m[1]); i < len(cfg.Exceptions) {
				e := &cfg.Exceptions[i]
				e.invalid = strings.TrimPrefix(e.invalid+"; "+v.String(), "; ")
				continue
			}
		}
		errs = append(errs, v.String())
	}
	return cfg, errs, nil
}

func tagIgnores(list []Ignore, o origin, pointer string) {
	for i := range list {
		list[i].origin = origin{file: o.file, pointer: pointer + "/ignore/" + strconv.Itoa(i), inRepo: o.inRepo}
	}
}

// validate checks what the schema cannot: check ids, regexes, locked checks and gate checks. locked comes from infra.
func validate(c, infra *Config) []string {
	var errs []string
	add := func(format string, args ...any) { errs = append(errs, fmt.Sprintf(format, args...)) }
	locked := func(id string) bool { return slices.Contains(infra.Locked, id) }
	checkSettings := func(where, eco string, checks map[string]CheckSettings) {
		for _, id := range slices.Sorted(maps.Keys(checks)) {
			s := checks[id]
			switch {
			case !knownCheck(id):
				add("%s: unknown check %q", where, id)
				continue
			case eco != "" && (isGateCheck(id) || !appliesTo(id, eco)):
				add("%s: check %q cannot be set for %s", where, id, eco)
			case c == infra && isGateCheck(id) && s.Level == Off:
				add("%s: check %q of the gate cannot be turned off", where, id)
			}
			if len(s.Ignore) > 0 && (isGateCheck(id) || c != infra && locked(id)) {
				add("%s: check %q cannot have ignore rules", where, id)
			}
			for _, ig := range s.Ignore {
				if _, err := ig.compile(); err != nil {
					add("%s: %v", ig.origin.pointer, err)
				}
			}
			// a repository may only raise locked checks and checks of the gate
			if c == infra || s.Level == 0 || !locked(id) && !isGateCheck(id) {
				continue
			}
			for _, e := range ecosystemsOf(id, eco) {
				if floor := infra.level(id, e, "", nil); s.Level < floor {
					add("%s: check %q cannot be lower than %s", where, id, floor)
					break
				}
			}
		}
	}
	for _, id := range c.Locked {
		if !knownCheck(id) {
			add("locked: unknown check %q", id)
		}
	}
	checkSettings("checks", "", c.Checks)
	for _, eco := range slices.Sorted(maps.Keys(c.Ecosystems)) {
		e := c.Ecosystems[eco]
		checkSettings("ecosystems."+eco+".checks", eco, e.Checks)
	}
	// maven excludes are Java regexes, the plugin checks them
	var goExclude []string
	if err := c.Ecosystems[Go].Setting("exclude", &goExclude); err != nil {
		add("ecosystems.go.exclude: %v", err)
	}
	for _, rx := range goExclude {
		if _, err := regexp.Compile(rx); err != nil {
			add("ecosystems.go.exclude: %v", err)
		}
	}
	for label, levels := range c.Labels.Levels {
		for id := range levels {
			switch {
			case !knownCheck(id):
				add("labels.levels.%s: unknown check %q", label, id)
			case locked(id) || isGateCheck(id):
				add("labels.levels.%s: check %q cannot be changed by labels", label, id)
			}
		}
	}
	for _, f := range c.Families {
		if _, err := regexp.Compile(f.Match); err != nil {
			add("families.%s: %v", f.Name, err)
		}
	}
	if _, err := regexp.Compile(c.ProtectedBranches); err != nil {
		add("protectedBranches: %v", err)
	}
	return errs
}

// restricted reports settings only the infra defaults may make.
func restricted(repo *Config) []string {
	var errs []string
	for name, set := range map[string]bool{
		"protectedBranches": repo.ProtectedBranches != "",
		"maxExceptionDays":  repo.MaxExceptionDays != 0,
		"locked":            repo.Locked != nil,
		"consumers":         len(repo.Consumers) > 0 && string(bytes.TrimSpace(repo.Consumers)) != "false",
		// the pins are what locked checks compare with
		"ecosystems.maven.pins": repo.Ecosystems[Maven].Settings["pins"] != nil,
	} {
		if set {
			errs = append(errs, name+" is set by the infra defaults only")
		}
	}
	slices.Sort(errs)
	return errs
}

func ecosystemsOf(id, eco string) []string {
	if eco != "" {
		return []string{eco}
	}
	var out []string
	for _, e := range []string{Maven, Go} {
		if appliesTo(id, e) {
			out = append(out, e)
		}
	}
	return out
}

// level of a check before branch rules: catalog, checks, ecosystem checks, then PR labels (the highest wins).
func (c *Config) level(check, eco, adapter string, labels []string) Level {
	l := defaultLevel(check, eco, adapter)
	if s := c.Checks[check]; s.Level != 0 {
		l = s.Level
	}
	if s := c.Ecosystems[eco].Checks[check]; s.Level != 0 {
		l = s.Level
	}
	var byLabel Level
	for _, label := range labels {
		byLabel = max(byLabel, c.Labels.Levels[label][check])
	}
	if byLabel != 0 {
		l = byLabel
	}
	return l
}

// Level is the effective level of a check for a finding.
func (p *Policy) Level(check, eco, adapter string, labels []string, branch string) Level {
	if catalog[check].protectedOnly && branch != "" && !p.protected.MatchString(branch) {
		return Off
	}
	return p.Config.level(check, eco, adapter, labels)
}

func merge(infra, repo Config) Config {
	out := infra
	if repo.Mode != "" {
		out.Mode = repo.Mode
	}
	out.Labels.Levels = mergeMap(infra.Labels.Levels, repo.Labels.Levels,
		func(a, b map[string]Level) map[string]Level { return mergeMap(a, b, nil) })
	out.Checks = mergeMap(infra.Checks, repo.Checks, mergeCheck)
	out.Ecosystems = mergeMap(infra.Ecosystems, repo.Ecosystems, func(a, b Ecosystem) Ecosystem {
		return Ecosystem{Checks: mergeMap(a.Checks, b.Checks, mergeCheck), Settings: mergeMap(a.Settings, b.Settings, nil)}
	})
	out.Families = slices.Clone(infra.Families)
	for _, f := range repo.Families {
		if i := slices.IndexFunc(out.Families, func(g Family) bool { return g.Name == f.Name }); i >= 0 {
			out.Families[i] = f
		} else {
			out.Families = append(out.Families, f)
		}
	}
	if len(repo.Consumers) > 0 {
		out.Consumers = repo.Consumers
	}
	out.Exceptions = slices.Concat(infra.Exceptions, repo.Exceptions)
	return out
}

func mergeCheck(a, b CheckSettings) CheckSettings {
	out := a
	if b.Level != 0 {
		out.Level = b.Level
	}
	out.Ignore = slices.Concat(a.Ignore, b.Ignore)
	if b.FailOnSeverity != "" {
		out.FailOnSeverity = b.FailOnSeverity
	}
	if b.Allow != nil {
		out.Allow = b.Allow
	}
	return out
}

// mergeMap overlays over on base; combine merges values present in both, otherwise over wins.
func mergeMap[V any](base, over map[string]V, combine func(V, V) V) map[string]V {
	if len(base)+len(over) == 0 {
		return nil
	}
	out := make(map[string]V, len(base)+len(over))
	maps.Copy(out, base)
	for k, v := range over {
		if old, ok := out[k]; ok && combine != nil {
			v = combine(old, v)
		}
		out[k] = v
	}
	return out
}

func compileIgnores(c Config) (map[string][]*rule, error) {
	out := map[string][]*rule{}
	add := func(key string, list []Ignore) error {
		for _, ig := range list {
			sel, err := ig.compile()
			if err != nil {
				return fmt.Errorf("%s: %w", ig.origin, err)
			}
			out[key] = append(out[key], &rule{selector: sel, ref: ExceptionRef{Source: ig.origin.String(), Reason: ig.Reason}})
		}
		return nil
	}
	for id, s := range c.Checks {
		if err := add(id, s.Ignore); err != nil {
			return nil, err
		}
	}
	for eco, e := range c.Ecosystems {
		for id, s := range e.Checks {
			if err := add(id+"|"+eco, s.Ignore); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

// addException compiles an exception, or reports why it cannot apply.
func (p *Policy) addException(e Exception, today time.Time) {
	sel, err := e.compile()
	if err != nil {
		p.problem(ExceptionInvalid, e.origin, "%v", err)
		return
	}
	for _, pattern := range e.Checks {
		ids := matchChecks(pattern)
		if len(ids) == 0 {
			p.problem(ExceptionInvalid, e.origin, "checks: %q matches no check", pattern)
			return
		}
		// a locked check is excepted one package version at a time
		if i := slices.IndexFunc(ids, func(id string) bool { return slices.Contains(p.Config.Locked, id) }); i >= 0 &&
			e.origin.inRepo && !(exact(e.Package) && exact(e.Version)) {
			p.problem(ExceptionInvalid, e.origin, "checks: %q covers the locked check %s, so package and version must be exact", pattern, ids[i])
			return
		}
	}
	until, err := time.Parse(time.DateOnly, e.Until)
	if err != nil {
		p.problem(ExceptionInvalid, e.origin, "until: %v", err)
		return
	}
	switch days := p.Config.MaxExceptionDays; {
	case until.After(today.AddDate(0, 0, days)):
		p.problem(ExceptionInvalid, e.origin, "until %s is more than %d days ahead", e.Until, days)
		return
	case until.Before(today):
		p.problem(ExceptionExpired, e.origin, "exception for %s expired on %s (reason: %s)", e.Selector, e.Until, e.Reason)
		return
	case until.Before(today.AddDate(0, 0, expiringDays)):
		p.problem(ExceptionExpiring, e.origin, "exception for %s expires on %s (reason: %s)", e.Selector, e.Until, e.Reason)
	}
	p.exceptions = append(p.exceptions, &rule{
		selector: sel,
		checks:   e.Checks,
		ref:      ExceptionRef{Source: e.origin.String(), Reason: e.Reason, Until: e.Until, Issue: e.Issue},
		at:       e.origin.locations(),
		what:     e.Selector.String(),
	})
}
