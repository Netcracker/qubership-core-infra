package gate

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// expandConsumers fills consumers.go.libs, consumers.go.services and consumers.maven.services
// from .github/repos.json. A group that is already an array is left as it is.
// Module comes from the list; tests and the test-app modules come from the defaults.
func expandConsumers(cfg *Config, path string) error {
	if len(cfg.Consumers) == 0 || string(bytes.TrimSpace(cfg.Consumers)) == "false" {
		return nil
	}
	var doc struct {
		Go struct {
			Libs     json.RawMessage `json:"libs"`
			Services json.RawMessage `json:"services"`
		} `json:"go"`
		Maven struct {
			Services json.RawMessage `json:"services"`
		} `json:"maven"`
	}
	if err := json.Unmarshal(cfg.Consumers, &doc); err != nil {
		return fmt.Errorf("consumers: %w", err)
	}
	needs, err := groupsNeedFile(doc.Go.Libs, doc.Go.Services, doc.Maven.Services)
	if err != nil || !needs {
		return err
	}
	if path == "" {
		path = findListsFile()
	}
	if path == "" {
		return fmt.Errorf("consumers need .github/repos.json; pass -consumer-lists")
	}
	repos, err := readRepos(path)
	if err != nil {
		return err
	}
	libs, err := expandGroup(repos.Go.Libs, doc.Go.Libs, true)
	if err != nil {
		return fmt.Errorf("consumers.go.libs: %w", err)
	}
	services, err := expandGroup(repos.Go.Services, doc.Go.Services, true)
	if err != nil {
		return fmt.Errorf("consumers.go.services: %w", err)
	}
	maven, err := expandGroup(repos.Maven.Services, doc.Maven.Services, false)
	if err != nil {
		return fmt.Errorf("consumers.maven.services: %w", err)
	}
	out, err := json.Marshal(map[string]any{
		"go":    map[string]any{"libs": libs, "services": services},
		"maven": map[string]any{"services": maven},
	})
	if err != nil {
		return err
	}
	cfg.Consumers = out
	return nil
}

func groupsNeedFile(groups ...json.RawMessage) (bool, error) {
	needs := false
	for _, raw := range groups {
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 || bytes.HasPrefix(raw, []byte("[")) {
			continue
		}
		if !bytes.HasPrefix(raw, []byte("{")) {
			return false, fmt.Errorf("consumers: expected an object or an array")
		}
		needs = true
	}
	return needs, nil
}

type listedRepo struct {
	Repo   string `json:"repo"`
	Module string `json:"module"`
}

type repoLists struct {
	Go struct {
		Libs     []listedRepo `json:"libs"`
		Services []listedRepo `json:"services"`
	} `json:"go"`
	Maven struct {
		Services []listedRepo `json:"services"`
	} `json:"maven"`
}

func readRepos(path string) (repoLists, error) {
	var lists repoLists
	data, err := os.ReadFile(path)
	if err != nil {
		return lists, err
	}
	if err := json.Unmarshal(data, &lists); err != nil {
		return lists, fmt.Errorf("%s: %w", filepath.Base(path), err)
	}
	return lists, nil
}

type consumerEntry struct {
	Repo   string `json:"repo"`
	Module string `json:"module"`
	Tests  bool   `json:"tests,omitempty"`
}

func expandGroup(repos []listedRepo, raw json.RawMessage, runTests bool) ([]consumerEntry, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 {
		return nil, nil
	}
	if raw[0] == '[' {
		var list []consumerEntry
		if err := json.Unmarshal(raw, &list); err != nil {
			return nil, err
		}
		return list, nil
	}
	var g struct {
		Tests bool `json:"tests"`
		Extra []struct {
			Repo   string `json:"repo"`
			Module string `json:"module"`
			Tests  *bool  `json:"tests"`
		} `json:"extra"`
	}
	if err := json.Unmarshal(raw, &g); err != nil {
		return nil, err
	}
	if len(repos) == 0 {
		return nil, fmt.Errorf("empty")
	}
	var out []consumerEntry
	for _, repo := range repos {
		if repo.Repo == "" {
			return nil, fmt.Errorf("repository name is empty")
		}
		module := repo.Module
		if module == "" {
			module = "."
		}
		out = append(out, consumerEntry{Repo: repo.Repo, Module: module, Tests: runTests && g.Tests})
	}
	for _, extra := range g.Extra {
		tests := runTests && g.Tests
		if extra.Tests != nil {
			tests = runTests && *extra.Tests
		}
		module := extra.Module
		if module == "" {
			module = "."
		}
		out = append(out, consumerEntry{Repo: extra.Repo, Module: module, Tests: tests})
	}
	return out, nil
}

// findListsFile walks up from the working directory to .github/repos.json.
func findListsFile() string {
	dir, err := os.Getwd()
	if err != nil {
		return ""
	}
	for {
		candidate := filepath.Join(dir, ".github", "repos.json")
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return ""
		}
		dir = parent
	}
}
