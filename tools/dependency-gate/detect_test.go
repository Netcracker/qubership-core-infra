package main

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/Netcracker/qubership-core-infra/tools/dependency-gate/internal/gate"
)

func TestDetectEcosystems(t *testing.T) {
	topLevel := map[string]string{
		"parent/pom.xml":    `<project><modules><module>../bom</module></modules><profiles><profile><modules><module>../extra</module></modules></profile></profiles></project>`,
		"bom/pom.xml":       "<project/>",
		"extra/pom.xml":     "<project/>",
		"tools/pom.xml":     "<project/>",
		"docs/readme.md":    "",
		"service/go.mod":    "module example.com/service",
		"service/go.sum":    "",
		"vendor/x/go.mod":   "module x",
		"a/testdata/go.mod": "module td",
		".git/go.mod":       "module hidden",
		"go.mod":            "module example.com/root",
	}
	configured := map[string]string{"pom.xml": "<project/>", "go.mod": "module m", "test-apps/a/go.mod": "module a"}
	for _, tc := range []struct {
		name       string
		files      map[string]string
		ecosystems string
		changed    []string
		want       detection
	}{
		{"root pom", map[string]string{"pom.xml": "<project/>", "lib/pom.xml": "<project/>"}, "", []string{"README.md"},
			detection{mavenRoots: []string{"."}, goModules: []string{}}},
		// poms listed as modules of another top-level pom, profiles included, are not roots
		{"top-level poms, go.sum changed", topLevel, "", []string{"service/go.sum", "tools/src/Main.java"},
			detection{mavenRoots: []string{"parent", "tools"}, checkGo: true, goModules: []string{".", "service"}}},
		// push, manual run: everything present is checked
		{"top-level poms, everything", topLevel, "", nil,
			detection{checkMaven: true, mavenRoots: []string{"parent", "tools"}, checkGo: true, goModules: []string{".", "service"}}},
		{"top-level poms, maven config changed", topLevel, "", []string{".mvn/maven.config"},
			detection{checkMaven: true, mavenRoots: []string{"parent", "tools"}, goModules: []string{".", "service"}}},
		{"configured roots and excludes", configured, `{"maven": {"roots": ["core"]}, "go": {"exclude": ["^test-apps/"]}}`, nil,
			detection{checkMaven: true, mavenRoots: []string{"core"}, checkGo: true, goModules: []string{"."}}},
		{"configured modules", configured, `{"go": {"modules": ["test-apps/a"]}}`, nil,
			detection{checkMaven: true, mavenRoots: []string{"."}, checkGo: true, goModules: []string{"test-apps/a"}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			for name, content := range tc.files {
				write(t, filepath.Join(dir, name), content)
			}
			var cfg gate.Config
			if tc.ecosystems != "" {
				if err := json.Unmarshal([]byte(`{"ecosystems": `+tc.ecosystems+`}`), &cfg); err != nil {
					t.Fatal(err)
				}
			}
			got, err := detect(dir, &cfg, tc.changed)
			if err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff(tc.want, *got, cmp.AllowUnexported(detection{})); diff != "" {
				t.Errorf("(-want +got):\n%s", diff)
			}
		})
	}
}
