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

// stickyComment creates or updates the comment of the gate on the pull request of the run.
func stickyComment(ctx context.Context, gh *githubactions.GitHubContext, pr int, body string, create bool) error {
	token := os.Getenv("GITHUB_TOKEN")
	if token == "" {
		return errors.New("GITHUB_TOKEN is not set")
	}
	client, err := newGitHubClient(token, gh.APIURL, gh.Repository)
	if err != nil {
		return err
	}
	return client.upsertComment(ctx, pr, render.Marker, body, create)
}

// upsertComment keeps one PR comment starting with marker: it is updated when present and created only if create.
// Only comments of a bot count: anyone can post the marker.
func (c *githubClient) upsertComment(ctx context.Context, pr int, marker, body string, create bool) error {
	var id int64
	opts := &github.IssueListCommentsOptions{ListOptions: github.ListOptions{PerPage: 100}}
	for comment, err := range c.gh.Issues.ListCommentsIter(ctx, c.owner, c.repo, pr, opts) {
		if err != nil {
			return err
		}
		if comment.GetUser().GetType() == "Bot" && strings.HasPrefix(comment.GetBody(), marker) {
			id = comment.GetID()
		}
	}
	var err error
	switch {
	case id != 0:
		_, _, err = c.gh.Issues.UpdateComment(ctx, c.owner, c.repo, id, github.IssueCommentRequest{Body: body})
	case create:
		_, _, err = c.gh.Issues.CreateComment(ctx, c.owner, c.repo, pr, github.IssueCommentRequest{Body: body})
	}
	return err
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
