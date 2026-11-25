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

	"github.com/Netcracker/qubership-core-infra/dependency-gate/internal/gate"
	"github.com/Netcracker/qubership-core-infra/dependency-gate/internal/render"
)

//go:embed defaults.json
var defaults []byte

//go:embed schemas/*.json
var schemas embed.FS

const usage = `usage: dependency-gate <command> [flags]

commands:
  evaluate  apply the policy to adapter findings and write the report (exit 0: pass, warn, or dry-run; 1: a new fail; 2: the gate itself failed)
  detect    find the Maven roots and Go modules and whether their manifests changed
  compare   print -1, 0 or 1 as Go version <a> is below, equal to or above <b> (for the go-dep-check scripts)

Run "dependency-gate <command> -h" for the flags.
`

var (
	// errFailed is a new fail. A dry run does not return it.
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
		return evaluate(args, stdout)
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
	repo, configRev, defaults, today, lists string
}

func (o *options) register(fs *flag.FlagSet) {
	fs.StringVar(&o.repo, "repo", ".", "root of the checked repository")
	fs.StringVar(&o.configRev, "config-rev", "",
		"git revision to read "+gate.RepoConfigFile+" from (default: HEAD^1 on pull requests, so a change cannot relax its own gate; else the working tree)")
	fs.StringVar(&o.defaults, "defaults", "", "infra defaults file to use instead of the built-in one")
	fs.StringVar(&o.today, "today", "", "date exceptions expire against, yyyy-mm-dd (default: today)")
	fs.StringVar(&o.lists, "consumer-lists", "", "path of .github/repos.json (names and module directories)")
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
	p, err := gate.Load(gate.LoadOptions{Schemas: s, Defaults: data, DefaultsName: name, RepoDir: o.repo, RepoRev: rev, Today: today, ListsFile: o.lists})
	return s, p, err
}

func evaluate(args []string, stdout io.Writer) error {
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
	comment := fs.Bool("comment", false, "this run owns the Dependency gate comment: comment.md, output comment=post or update")
	commentNote := fs.Bool("comment-note", false, "this run owns the Consumers comment: comment.md is the fail fact and the run link, output comment=post or delete")
	dryRun := fs.Bool("dry-run", false, "do not fail the check; the summary and comment show a warning with the same findings")
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
	base, head := "", gh.SHA
	if pullRequest() {
		ev, err := pullRequestEvent(gh.EventPath)
		if err != nil {
			return fmt.Errorf("pull request event: %w", err)
		}
		pr, base, head = ev.Number, ev.BaseSHA, ev.HeadSHA
		in.Branch = cmp.Or(in.Branch, ev.BaseRef)
		in.Labels = append(in.Labels, ev.Labels...)
	} else {
		in.Branch = cmp.Or(in.Branch, gh.RefName)
	}
	if *findingsDir != "" {
		if err := in.ReadFindings(s, *findingsDir); err != nil {
			return err
		}
	}

	report := policy.Evaluate(in)
	report.DryRun = *dryRun
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
	// The workflow step publishes comment.md. This process only chooses the body and whether to
	// create, refresh or delete that comment.
	commentBody := render.Comment(report, runURL)
	commentMode := ""
	switch {
	case *comment:
		commentMode = "update"
		if render.NeedsComment(report) {
			commentMode = "post"
		}
	case *commentNote:
		commentBody = render.ConsumerComment(report, runURL)
		commentMode = "delete"
		if commentBody != "" {
			commentMode = "post"
		}
	}
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
		if commentMode != "" {
			actions.SetOutput("comment", commentMode)
		}
	} else {
		fmt.Fprint(stdout, summary)
	}
	slog.Info("dependency gate", "dry-run", report.DryRun, "verdict", report.Verdict, "fail", report.Counts.Fail,
		"warn", report.Counts.Warn, "preexisting", report.Counts.Preexisting, "allowed", report.Counts.Allowed)
	if report.Failed() {
		return errFailed
	}
	return nil
}

// prEvent is the part of a pull_request payload the gate reads: the number, the base
// branch and the labels. The workflow posts the comments.
type prEvent struct {
	Number  int
	BaseRef string
	BaseSHA string
	HeadSHA string
	Labels  []string
}

func pullRequestEvent(path string) (prEvent, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return prEvent{}, err
	}
	var raw struct {
		PullRequest struct {
			Number int `json:"number"`
			Base   struct {
				Ref string `json:"ref"`
				SHA string `json:"sha"`
			} `json:"base"`
			Head struct {
				SHA string `json:"sha"`
			} `json:"head"`
			Labels []struct {
				Name string `json:"name"`
			} `json:"labels"`
		} `json:"pull_request"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return prEvent{}, err
	}
	ev := prEvent{
		Number: raw.PullRequest.Number, BaseRef: raw.PullRequest.Base.Ref,
		BaseSHA: raw.PullRequest.Base.SHA, HeadSHA: raw.PullRequest.Head.SHA,
	}
	for _, l := range raw.PullRequest.Labels {
		ev.Labels = append(ev.Labels, l.Name)
	}
	return ev, nil
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
