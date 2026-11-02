// Command dependency-gate applies the dependency policy (levels, exceptions, baseline) to the findings
// of the adapters and decides whether a dependency change may be merged without a reviewer.
package main

import (
	"cmp"
	"context"
	"embed"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	goversion "go/version"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"time"

	"github.com/sethvargo/go-githubactions"
	"golang.org/x/mod/semver"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/render"
)

//go:embed defaults.json
var defaults []byte

//go:embed schemas/*.json
var schemas embed.FS

const usage = `usage: dependency-gate <command> [flags]

commands:
  evaluate  apply the policy to adapter findings and write the report (exit 0: pass/warn, 1: fail, 2: error)
  detect    find the Maven roots and Go modules and whether their manifests changed
  compare   print -1, 0 or 1 as Go version <a> is below, equal to or above <b> (for the go-dep-check scripts)

Run "dependency-gate <command> -h" for the flags.
`

var (
	// errFailed is a fail verdict in enforce mode.
	errFailed = errors.New("dependency gate failed")
	errUsage  = errors.New("unknown command")
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, nil)))
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	switch err := run(context.Background(), os.Args[1], os.Args[2:], os.Stdout); {
	case errors.Is(err, errFailed):
		os.Exit(1)
	case errors.Is(err, flag.ErrHelp):
		os.Exit(0)
	case errors.Is(err, errUsage):
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	case err != nil:
		slog.Error(err.Error())
		os.Exit(2)
	}
}

// run executes one command; stdout gets the local output and, in GitHub Actions, the workflow commands.
func run(ctx context.Context, cmd string, args []string, stdout io.Writer) error {
	switch cmd {
	case "evaluate":
		return evaluate(ctx, args, stdout)
	case "detect":
		return detectCmd(args, stdout)
	case "compare":
		if len(args) != 2 {
			return errors.New("usage: dependency-gate compare <a> <b>")
		}
		_, err := fmt.Fprintln(stdout, compareVersions(args[0], args[1]))
		return err
	}
	return errUsage
}

// compareVersions orders Go module versions (semver, pre-releases and pseudo-versions included: v1.2.0-rc.1 < v1.2.0)
// or, without the v prefix, Go versions of a go directive (1.21rc1 < 1.21.0 < 1.22). An invalid version is the lowest.
func compareVersions(a, b string) int {
	if strings.HasPrefix(a, "v") || strings.HasPrefix(b, "v") {
		return semver.Compare(a, b)
	}
	return goversion.Compare("go"+a, "go"+b)
}

func inActions() bool { return os.Getenv("GITHUB_ACTIONS") == "true" }

func pullRequest() bool { return strings.HasPrefix(os.Getenv("GITHUB_EVENT_NAME"), "pull_request") }

// common flags of the commands
type options struct {
	repo, configRev, defaults, today string
}

func (o *options) register(fs *flag.FlagSet) {
	fs.StringVar(&o.repo, "repo", ".", "root of the checked repository")
	fs.StringVar(&o.configRev, "config-rev", "",
		"git revision to read "+gate.RepoConfigFile+" from (default: HEAD^1 on pull requests, so a change cannot relax its own gate; else the working tree)")
	fs.StringVar(&o.defaults, "defaults", "", "infra defaults file to use instead of the built-in one")
	fs.StringVar(&o.today, "today", "", "date exceptions expire against, yyyy-mm-dd (default: today)")
}

// load compiles the built-in schemas and loads the policy.
func (o *options) load() (*gate.Schemas, *gate.Policy, error) {
	today := time.Now().UTC().Truncate(24 * time.Hour)
	if o.today != "" {
		var err error
		if today, err = time.Parse(time.DateOnly, o.today); err != nil {
			return nil, nil, fmt.Errorf("-today: %w", err)
		}
	}
	data, name := defaults, "infra:defaults.json@"+version()
	if o.defaults != "" {
		var err error
		if data, err = os.ReadFile(o.defaults); err != nil {
			return nil, nil, err
		}
		name = filepath.ToSlash(o.defaults)
	}
	rev := o.configRev
	if rev == "" && pullRequest() {
		// the pull request merge commit: its first parent is the base branch
		rev = "HEAD^1"
	}
	sub, err := fs.Sub(schemas, "schemas")
	if err != nil {
		return nil, nil, err
	}
	s, err := gate.LoadSchemas(sub)
	if err != nil {
		return nil, nil, err
	}
	p, err := gate.Load(gate.LoadOptions{Schemas: s, Defaults: data, DefaultsName: name, RepoDir: o.repo, RepoRev: rev, Today: today})
	return s, p, err
}

func evaluate(ctx context.Context, args []string, stdout io.Writer) error {
	var o options
	in := gate.Input{Expected: map[string]string{}}
	fs := flag.NewFlagSet("evaluate", flag.ContinueOnError)
	o.register(fs)
	findingsDir := fs.String("findings", "", "directory with the findings.v1 files of the adapters, head and base")
	outDir := fs.String("out", "", "directory to write report.json, summary.md and comment.md to")
	fs.StringVar(&in.Branch, "branch", "", "target branch (default: from the GitHub event)")
	fs.Func("label", "pull request label, repeatable (in GitHub Actions read from the event)", func(v string) error {
		in.Labels = append(in.Labels, v)
		return nil
	})
	fs.Func("expect", "adapter=<job result> of an adapter that must have reported, repeatable", func(v string) error {
		name, result, ok := strings.Cut(v, "=")
		if !ok {
			return errors.New("want adapter=result")
		}
		in.Expected[name] = result
		return nil
	})
	comment := fs.Bool("comment", false, "create or update the sticky PR comment (GitHub Actions, needs GITHUB_TOKEN)")
	gateVersion := fs.String("gate", "qubership-core-infra@"+version(), "gate version written to the report")
	if err := fs.Parse(args); err != nil {
		return err
	}

	s, policy, err := o.load()
	if err != nil {
		return err
	}

	actions := githubactions.New(githubactions.WithWriter(stdout))
	gh, err := actions.Context()
	if err != nil {
		return err
	}
	var pr int
	var fork bool
	base, head := "", gh.SHA
	if pullRequest() {
		ev, err := pullRequestEvent(gh.EventPath)
		if err != nil {
			return fmt.Errorf("pull request event: %w", err)
		}
		p := ev.GetPullRequest()
		pr, base, head = p.GetNumber(), p.GetBase().GetSHA(), p.GetHead().GetSHA()
		fork = p.GetHead().GetRepo().GetFullName() != p.GetBase().GetRepo().GetFullName()
		in.Branch = cmp.Or(in.Branch, p.GetBase().GetRef())
		for _, l := range p.Labels {
			in.Labels = append(in.Labels, l.GetName())
		}
	} else {
		in.Branch = cmp.Or(in.Branch, gh.RefName)
	}
	if *findingsDir != "" {
		if err := in.ReadFindings(s, *findingsDir); err != nil {
			return err
		}
	}

	report := policy.Evaluate(in)
	report.Repository, report.PullRequest, report.Base, report.Head, report.Gate = gh.Repository, pr, base, head, *gateVersion
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	if err := s.ValidateReport(data); err != nil {
		return err
	}
	summary := render.Summary(report)
	runURL := ""
	if gh.RunID != 0 {
		runURL = fmt.Sprintf("%s/%s/actions/runs/%d", gh.ServerURL, gh.Repository, gh.RunID)
	}
	commentBody := render.Comment(report, runURL)
	if *outDir != "" {
		if err := writeFiles(*outDir, map[string]string{
			"report.json": string(data) + "\n", "summary.md": summary, "comment.md": commentBody,
		}); err != nil {
			return err
		}
	}

	if inActions() {
		actions.AddStepSummary(summary)
		render.Annotate(actions, report)
		actions.SetOutput("verdict", string(report.Verdict))
	} else {
		fmt.Fprint(stdout, summary)
	}
	if *comment && pr != 0 && !fork {
		if err := stickyComment(ctx, gh, pr, commentBody, render.NeedsComment(report)); err != nil {
			// the verdict stands without the comment
			actions.Warningf("cannot update the PR comment: %v", err)
		}
	}
	slog.Info("dependency gate", "mode", report.Mode, "verdict", report.Verdict, "fail", report.Counts.Fail,
		"warn", report.Counts.Warn, "preexisting", report.Counts.Preexisting, "allowed", report.Counts.Allowed)
	if report.Failed() {
		return errFailed
	}
	return nil
}

func writeFiles(dir string, files map[string]string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
			return err
		}
	}
	return nil
}

// version is the module version for go run ...@<sha>, else the VCS revision of the build, else "dev".
func version() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "dev"
	}
	if v := info.Main.Version; v != "" && v != "(devel)" {
		return v
	}
	for _, s := range info.Settings {
		if s.Key == "vcs.revision" {
			return s.Value
		}
	}
	return "dev"
}
