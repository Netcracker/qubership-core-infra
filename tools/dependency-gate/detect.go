package main

import (
	"encoding/json"
	"encoding/xml"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"slices"
	"strings"

	"github.com/sethvargo/go-githubactions"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
)

func detectCmd(args []string, stdout io.Writer) error {
	var o options
	fs := flag.NewFlagSet("detect", flag.ContinueOnError)
	o.register(fs)
	base := fs.String("base", "", "revision to compare HEAD with (default: HEAD^1 on pull requests; empty: check everything)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	_, policy, err := o.load()
	if err != nil {
		return err
	}
	for _, p := range policy.Problems {
		// reported by evaluate; detect only works with what is valid
		slog.Warn(p.Message, "check", p.Check, "at", p.Package)
	}
	if *base == "" && pullRequest() {
		*base = "HEAD^1"
	}
	var changed []string
	if *base != "" {
		if changed, err = changedFiles(o.repo, *base); err != nil {
			return fmt.Errorf("changed files since %s: %w", *base, err)
		}
	}
	d, err := detect(o.repo, &policy.Config, changed)
	if err != nil {
		return err
	}
	outputs := map[string]any{
		"maven": d.checkMaven, "maven-roots": d.mavenRoots, "go": d.checkGo, "go-modules": d.goModules, "config": policy.Config,
	}
	values := map[string]string{}
	for k, v := range outputs {
		data, err := json.Marshal(v)
		if err != nil {
			return err
		}
		values[k] = string(data)
	}
	if !inActions() {
		out, err := json.MarshalIndent(values, "", "  ")
		fmt.Fprintln(stdout, string(out))
		return err
	}
	a := githubactions.New(githubactions.WithWriter(stdout))
	for k, v := range values {
		a.SetOutput(k, v)
	}
	return nil
}

// detection tells which adapters have something to check.
type detection struct {
	checkMaven bool
	mavenRoots []string
	checkGo    bool
	goModules  []string
}

var (
	mavenManifest = regexp.MustCompile(`(^|/)pom\.xml$|^\.mvn/`)
	goManifest    = regexp.MustCompile(`(^|/)go\.(mod|sum)$`)
)

// detect finds the ecosystems of repo. changed lists the files the change touches; nil means everything is
// checked (push, manual runs).
func detect(repo string, cfg *gate.Config, changed []string) (*detection, error) {
	// outputs are JSON arrays, never null
	d := detection{mavenRoots: []string{}, goModules: []string{}}
	roots, err := mavenRoots(repo, cfg.Ecosystems[gate.Maven])
	if err != nil {
		return nil, err
	}
	modules, err := goModules(repo, cfg.Ecosystems[gate.Go])
	if err != nil {
		return nil, err
	}
	d.mavenRoots, d.goModules = append(d.mavenRoots, roots...), append(d.goModules, modules...)
	touches := func(rx *regexp.Regexp) bool { return changed == nil || slices.ContainsFunc(changed, rx.MatchString) }
	d.checkMaven = len(roots) > 0 && touches(mavenManifest)
	d.checkGo = len(modules) > 0 && touches(goManifest)
	return &d, nil
}

// changedFiles lists the files that differ between base and HEAD.
func changedFiles(repo, base string) ([]string, error) {
	out, err := exec.Command("git", "-C", repo, "diff", "--name-only", base, "HEAD").Output()
	return strings.Fields(string(out)), err
}

// mavenRoots: configured roots, else the root pom, else top-level poms that no other top-level pom lists as a module.
func mavenRoots(repo string, eco gate.Ecosystem) ([]string, error) {
	var roots []string
	if err := eco.Setting("roots", &roots); err != nil || roots != nil {
		return roots, err
	}
	if exists(filepath.Join(repo, "pom.xml")) {
		return []string{"."}, nil
	}
	entries, err := os.ReadDir(repo)
	if err != nil {
		return nil, err
	}
	modules := map[string]bool{}
	for _, e := range entries {
		pom := filepath.Join(repo, e.Name(), "pom.xml")
		if !e.IsDir() || !exists(pom) {
			continue
		}
		roots = append(roots, e.Name())
		listed, err := pomModules(pom)
		if err != nil {
			return nil, err
		}
		for _, m := range listed {
			modules[path.Clean(path.Join(e.Name(), filepath.ToSlash(m)))] = true
		}
	}
	return slices.DeleteFunc(roots, func(r string) bool { return modules[r] }), nil
}

func pomModules(pom string) ([]string, error) {
	data, err := os.ReadFile(pom)
	if err != nil {
		return nil, err
	}
	var project struct {
		Modules  []string `xml:"modules>module"`
		Profiles []struct {
			Modules []string `xml:"modules>module"`
		} `xml:"profiles>profile"`
	}
	if err := xml.Unmarshal(data, &project); err != nil {
		return nil, err
	}
	modules := project.Modules
	for _, p := range project.Profiles {
		modules = append(modules, p.Modules...)
	}
	return modules, nil
}

// goModules: configured modules, else every go.mod outside vendor, testdata and directories Go ignores.
func goModules(repo string, eco gate.Ecosystem) ([]string, error) {
	var modules, exclude []string
	if err := eco.Setting("modules", &modules); err != nil || modules != nil {
		return modules, err
	}
	if err := eco.Setting("exclude", &exclude); err != nil {
		return nil, err
	}
	var skip []*regexp.Regexp
	for _, rx := range exclude {
		re, err := regexp.Compile(rx)
		if err != nil {
			return nil, err
		}
		skip = append(skip, re)
	}
	err := filepath.WalkDir(repo, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(repo, p)
		rel = filepath.ToSlash(rel)
		if d.IsDir() {
			name := d.Name()
			if rel != "." && (name == "vendor" || name == "testdata" || strings.HasPrefix(name, ".") || strings.HasPrefix(name, "_")) {
				return filepath.SkipDir
			}
			return nil
		}
		dir := path.Dir(rel)
		if d.Name() == "go.mod" && !slices.ContainsFunc(skip, func(re *regexp.Regexp) bool { return re.MatchString(dir) }) {
			modules = append(modules, dir)
		}
		return nil
	})
	return modules, err
}

func exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
