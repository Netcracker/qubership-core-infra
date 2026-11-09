package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/google/go-github/v93/github"
	"github.com/sethvargo/go-githubactions"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/render"
)

// githubClient keeps the sticky PR comment.
type githubClient struct {
	gh          *github.Client
	owner, repo string
}

// newGitHubClient returns a client for repository (owner/name); apiURL is GITHUB_API_URL.
func newGitHubClient(token, apiURL, repository string) (*githubClient, error) {
	owner, repo, ok := strings.Cut(repository, "/")
	if !ok {
		return nil, fmt.Errorf("repository %q is not owner/name", repository)
	}
	opts := []github.ClientOptionsFunc{github.WithAuthToken(token), github.WithTimeout(time.Minute)}
	if apiURL != "" && apiURL != "https://api.github.com" {
		opts = append(opts, github.WithEnterpriseURLs(apiURL, apiURL))
	}
	gh, err := github.NewClient(opts...)
	if err != nil {
		return nil, err
	}
	return &githubClient{gh: gh, owner: owner, repo: repo}, nil
}

// stickyComment creates or updates the pin part of the comment. A consumers line already on it stays.
func stickyComment(ctx context.Context, gh *githubactions.GitHubContext, pr int, body string, create bool) error {
	client, err := githubClientFrom(gh)
	if err != nil {
		return err
	}
	return client.mergePin(ctx, pr, body, create)
}

// stickyConsumerNote sets or clears the consumers line. The pin part stays.
// jobName is the Actions job whose page has the findings; an unknown job falls back to runURL.
func stickyConsumerNote(ctx context.Context, gh *githubactions.GitHubContext, pr int, report *gate.Report, jobName, runURL string) error {
	client, err := githubClientFrom(gh)
	if err != nil {
		return err
	}
	details := runURL
	if jobName != "" && gh.RunID != 0 {
		if u, err := client.jobURL(ctx, gh.RunID, jobName); err == nil && u != "" {
			details = u
		}
	}
	return client.mergeNote(ctx, pr, render.ConsumerLine(report, details))
}

func githubClientFrom(gh *githubactions.GitHubContext) (*githubClient, error) {
	token := os.Getenv("GITHUB_TOKEN")
	if token == "" {
		return nil, errors.New("GITHUB_TOKEN is not set")
	}
	return newGitHubClient(token, gh.APIURL, gh.Repository)
}

// upsertComment keeps one PR comment starting with marker: it is updated when present and created only if create.
// Only comments of a bot count: anyone can post the marker.
func (c *githubClient) upsertComment(ctx context.Context, pr int, marker, body string, create bool) error {
	id, _, err := c.findComment(ctx, pr, marker)
	if err != nil {
		return err
	}
	switch {
	case id != 0:
		return c.writeComment(ctx, pr, id, body)
	case create:
		return c.writeComment(ctx, pr, 0, body)
	}
	return nil
}

// findComment returns the latest bot comment that starts with marker.
func (c *githubClient) findComment(ctx context.Context, pr int, marker string) (int64, string, error) {
	var id int64
	var body string
	opts := &github.IssueListCommentsOptions{ListOptions: github.ListOptions{PerPage: 100}}
	for comment, err := range c.gh.Issues.ListCommentsIter(ctx, c.owner, c.repo, pr, opts) {
		if err != nil {
			return 0, "", err
		}
		if comment.GetUser().GetType() == "Bot" && strings.HasPrefix(comment.GetBody(), marker) {
			id, body = comment.GetID(), comment.GetBody()
		}
	}
	return id, body, nil
}

func (c *githubClient) writeComment(ctx context.Context, pr int, id int64, body string) error {
	if body == "" {
		if id == 0 {
			return nil
		}
		_, err := c.gh.Issues.DeleteComment(ctx, c.owner, c.repo, id)
		return err
	}
	req := github.IssueCommentRequest{Body: body}
	var err error
	if id != 0 {
		_, _, err = c.gh.Issues.UpdateComment(ctx, c.owner, c.repo, id, req)
	} else {
		_, _, err = c.gh.Issues.CreateComment(ctx, c.owner, c.repo, pr, req)
	}
	return err
}

// mergePin writes the pin part and keeps a consumers line that is already there.
// After an update it reads once more: the consumers check may have added the line while this write was in flight.
func (c *githubClient) mergePin(ctx context.Context, pr int, pin string, create bool) error {
	id, existing, err := c.findComment(ctx, pr, render.Marker)
	if err != nil {
		return err
	}
	_, note := render.SplitComment(existing)
	next := render.JoinComment(pin, note)
	if (id == 0 && !create) || next == existing {
		return nil
	}
	if err := c.writeComment(ctx, pr, id, next); err != nil {
		return err
	}
	if id == 0 {
		return nil
	}
	_, now, err := c.findComment(ctx, pr, render.Marker)
	if err != nil || now == next || now == existing {
		return err
	}
	_, note = render.SplitComment(now)
	return c.writeComment(ctx, pr, id, render.JoinComment(pin, note))
}

// mergeNote sets the consumers line and keeps the pin part.
// An empty note removes the line. A comment that then has nothing left is deleted.
func (c *githubClient) mergeNote(ctx context.Context, pr int, note string) error {
	id, existing, err := c.findComment(ctx, pr, render.Marker)
	if err != nil {
		return err
	}
	pin, _ := render.SplitComment(existing)
	next := render.JoinComment(pin, note)
	if (id == 0 && next == "") || next == existing {
		return nil
	}
	if err := c.writeComment(ctx, pr, id, next); err != nil {
		return err
	}
	if id == 0 {
		return nil
	}
	_, now, err := c.findComment(ctx, pr, render.Marker)
	if err != nil || now == next || now == existing {
		return err
	}
	pin, _ = render.SplitComment(now)
	return c.writeComment(ctx, pr, id, render.JoinComment(pin, note))
}

// jobURL is the page of the named job in this run, where its step summary lists the findings.
func (c *githubClient) jobURL(ctx context.Context, runID int64, name string) (string, error) {
	opts := &github.ListWorkflowJobsOptions{Filter: "latest", ListOptions: github.ListOptions{PerPage: 100}}
	var page string
	var best int64
	for {
		jobs, resp, err := c.gh.Actions.ListWorkflowJobs(ctx, c.owner, c.repo, runID, opts)
		if err != nil {
			return "", err
		}
		for _, j := range jobs.Jobs {
			if j.GetName() == name && j.GetID() >= best {
				best, page = j.GetID(), j.GetHTMLURL()
			}
		}
		if resp.NextPage == 0 {
			return page, nil
		}
		opts.Page = resp.NextPage
	}
}

// pullRequestEvent reads the event payload of a pull_request or pull_request_target run.
func pullRequestEvent(path string) (*github.PullRequestEvent, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var ev github.PullRequestEvent
	return &ev, json.Unmarshal(data, &ev)
}
