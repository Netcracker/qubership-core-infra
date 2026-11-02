package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/google/go-cmp/cmp"
)

// api serves GitHub API routes under /api/v3, the prefix go-github adds to enterprise URLs.
type api struct {
	url    string
	mu     sync.Mutex
	routes map[string]http.HandlerFunc
	// requests in order, "METHOD path"
	calls []string
	// body of the last comment written
	body string
}

func newAPI(t *testing.T, routes map[string]http.HandlerFunc) *api {
	t.Helper()
	a := &api{routes: routes}
	srv := httptest.NewServer(a)
	t.Cleanup(srv.Close)
	a.url = srv.URL
	return a
}

func (a *api) client(t *testing.T) *githubClient {
	t.Helper()
	c, err := newGitHubClient("token", a.url, "o/r")
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func (a *api) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	route := r.Method + " " + strings.TrimPrefix(r.URL.Path, "/api/v3")
	var req struct{ Body string }
	data, _ := io.ReadAll(r.Body)
	_ = json.Unmarshal(data, &req)
	a.mu.Lock()
	a.calls = append(a.calls, route)
	if req.Body != "" {
		a.body = req.Body
	}
	a.mu.Unlock()
	if r.Header.Get("Authorization") != "Bearer token" {
		http.Error(w, "no token", http.StatusUnauthorized)
		return
	}
	h, ok := a.routes[route]
	if !ok {
		http.Error(w, "unexpected "+route, http.StatusNotFound)
		return
	}
	h(w, r)
}

func reply(v any) http.HandlerFunc {
	return replyStatus(http.StatusOK, v)
}

func replyStatus(status int, v any) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
}

func TestUpsertComment(t *testing.T) {
	const marker = "<!-- dependency-gate -->"
	comment := func(id int, body, login, kind string) map[string]any {
		return map[string]any{"id": id, "body": body, "user": map[string]any{"login": login, "type": kind}}
	}
	lgtm := comment(1, "LGTM", "maintainer", "User")
	gate := comment(2, marker+"\nold", "github-actions[bot]", "Bot")
	// anyone can post the marker: such a comment is never taken for the gate's own
	fake := comment(4, marker+"\nfake", "contributor", "User")
	existing := reply([]any{lgtm, gate})
	others := reply([]any{lgtm, fake})
	saved := replyStatus(http.StatusOK, map[string]any{"id": 3})
	for _, tc := range []struct {
		name    string
		routes  map[string]http.HandlerFunc
		create  bool
		calls   []string
		wantErr bool
	}{
		{"update", map[string]http.HandlerFunc{
			"GET /repos/o/r/issues/7/comments": existing, "PATCH /repos/o/r/issues/comments/2": saved,
		}, false, []string{"GET /repos/o/r/issues/7/comments", "PATCH /repos/o/r/issues/comments/2"}, false},
		{"create", map[string]http.HandlerFunc{
			"GET /repos/o/r/issues/7/comments": others, "POST /repos/o/r/issues/7/comments": replyStatus(http.StatusCreated, map[string]any{"id": 3}),
		}, true, []string{"GET /repos/o/r/issues/7/comments", "POST /repos/o/r/issues/7/comments"}, false},
		{"API error", map[string]http.HandlerFunc{}, true, []string{"GET /repos/o/r/issues/7/comments"}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := newAPI(t, tc.routes)
			err := a.client(t).upsertComment(t.Context(), 7, marker, marker+"\nnew", tc.create)
			if (err != nil) != tc.wantErr {
				t.Fatalf("error %v", err)
			}
			if diff := cmp.Diff(tc.calls, a.calls); diff != "" {
				t.Errorf("calls (-want +got):\n%s", diff)
			}
			if len(tc.calls) == 2 && a.body != marker+"\nnew" {
				t.Errorf("body %q", a.body)
			}
		})
	}
}
