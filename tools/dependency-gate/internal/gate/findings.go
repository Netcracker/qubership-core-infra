package gate

import (
	"bytes"
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"github.com/santhosh-tekuri/jsonschema/v6/kind"
	"golang.org/x/text/language"
	"golang.org/x/text/message"
)

const (
	FindingsSchema = "dependency-gate-findings/v1"
	ReportSchema   = "dependency-gate-report/v1"
)

type AdapterStatus string

const (
	AdapterOK      AdapterStatus = "ok"
	AdapterError   AdapterStatus = "error"
	AdapterSkipped AdapterStatus = "skipped"
)

type Revision string

const (
	Head Revision = "head"
	Base Revision = "base"
)

// Location of a finding in the checked repository.
type Location struct {
	File string `json:"file"`
	Line int    `json:"line,omitempty"`
	Unit string `json:"unit,omitempty"`
}

// Item is one raw finding of an adapter.
type Item struct {
	// set by the adapter when it covers several ecosystems, else taken from the adapter
	Ecosystem string     `json:"ecosystem,omitempty"`
	Check     string     `json:"check"`
	Package   string     `json:"package,omitempty"`
	Version   string     `json:"version,omitempty"`
	From      string     `json:"from,omitempty"`
	Symbol    string     `json:"symbol,omitempty"`
	Vuln      string     `json:"vuln,omitempty"`
	License   string     `json:"license,omitempty"`
	Message   string     `json:"message"`
	Note      string     `json:"note,omitempty"`
	Locations []Location `json:"locations,omitempty"`
	// found only in the resolved dependency tree, not declared or managed by the repository
	Transitive bool `json:"transitive,omitempty"`
}

// Adapter describes an adapter run in a report.
type Adapter struct {
	Name      string        `json:"name"`
	Ecosystem string        `json:"ecosystem,omitempty"`
	Tool      string        `json:"tool,omitempty"`
	Status    AdapterStatus `json:"status"`
	Reason    string        `json:"reason,omitempty"`
}

// FindingsFile is findings.v1: what one adapter found in one revision.
type FindingsFile struct {
	Schema  string `json:"schema"`
	Adapter struct {
		Adapter
		// checks that ran: only their exceptions can be unused
		Checks []string `json:"checks"`
	} `json:"adapter"`
	Revision Revision `json:"revision"`
	Findings []Item   `json:"findings"`
}

// ReadFindings adds every *.json under dir to the input. A file that does not match findings.v1 is a tool-error
// of its adapter: the gate fails closed.
func (in *Input) ReadFindings(s *Schemas, dir string) error {
	return filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || filepath.Ext(path) != ".json" {
			return err
		}
		switch f, problem := readFindings(s, path); {
		case problem != nil:
			in.Problems = append(in.Problems, problem)
		case f.Revision == Base:
			in.Base = append(in.Base, f)
		default:
			in.Head = append(in.Head, f)
		}
		return nil
	})
}

func readFindings(s *Schemas, path string) (*FindingsFile, *Finding) {
	var f FindingsFile
	data, err := os.ReadFile(path)
	if err == nil {
		var violations []SchemaError
		if violations, err = schemaViolations(s.findings, data); err == nil && len(violations) > 0 {
			err = fmt.Errorf("does not match findings.v1: %v", violations)
		}
	}
	if err == nil {
		err = json.Unmarshal(data, &f)
	}
	if err != nil {
		// name the adapter when the file says it
		_ = json.Unmarshal(data, &f)
		name := cmp.Or(f.Adapter.Name, strings.TrimSuffix(filepath.Base(path), ".json"))
		return nil, toolError(name, fmt.Sprintf("%s: %v", filepath.Base(path), err))
	}
	return &f, nil
}

func toolError(adapter, message string) *Finding {
	return &Finding{Adapter: adapter, Item: Item{Check: ToolError, Package: adapter, Message: message}}
}

var printer = message.NewPrinter(language.English)

// Schemas validates documents against the JSON schemas of the gate.
type Schemas struct {
	config, findings, report *jsonschema.Schema
}

// SchemaError is one violation, located by a JSON pointer into the document.
type SchemaError struct {
	Pointer string
	Message string
}

func (e SchemaError) String() string {
	if e.Pointer == "" {
		return e.Message
	}
	return e.Pointer + ": " + e.Message
}

// LoadSchemas compiles config.v1.json, findings.v1.json and report.v1.json from fsys.
func LoadSchemas(fsys fs.FS) (*Schemas, error) {
	c := jsonschema.NewCompiler()
	c.AssertFormat()
	compile := func(name string) (*jsonschema.Schema, error) {
		data, err := fs.ReadFile(fsys, name)
		if err != nil {
			return nil, err
		}
		doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(data))
		if err != nil {
			return nil, fmt.Errorf("%s: %w", name, err)
		}
		if err := c.AddResource(name, doc); err != nil {
			return nil, err
		}
		return c.Compile(name)
	}
	var s Schemas
	var errs [3]error
	s.config, errs[0] = compile("config.v1.json")
	s.findings, errs[1] = compile("findings.v1.json")
	s.report, errs[2] = compile("report.v1.json")
	if err := errors.Join(errs[:]...); err != nil {
		return nil, err
	}
	return &s, nil
}

// schemaViolations returns the violations of data; an error means data is not JSON at all.
func schemaViolations(schema *jsonschema.Schema, data []byte) ([]SchemaError, error) {
	doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	err = schema.Validate(doc)
	var verr *jsonschema.ValidationError
	if !errors.As(err, &verr) {
		return nil, err
	}
	return leaves(verr), nil
}

// leaves flattens the error tree to its specific causes; failed alternatives become one error.
func leaves(e *jsonschema.ValidationError) []SchemaError {
	switch e.ErrorKind.(type) {
	case *kind.AnyOf, *kind.OneOf:
		var alternatives []string
		for _, c := range e.Causes {
			for _, l := range leaves(c) {
				alternatives = append(alternatives, l.Message)
			}
		}
		return []SchemaError{{pointer(e.InstanceLocation), "no alternative matches: " + strings.Join(alternatives, "; ")}}
	}
	if len(e.Causes) == 0 {
		return []SchemaError{{pointer(e.InstanceLocation), e.ErrorKind.LocalizedString(printer)}}
	}
	var out []SchemaError
	for _, c := range e.Causes {
		out = append(out, leaves(c)...)
	}
	return out
}

func pointer(tokens []string) string {
	escape := strings.NewReplacer("~", "~0", "/", "~1")
	var b strings.Builder
	for _, t := range tokens {
		b.WriteString("/" + escape.Replace(t))
	}
	return b.String()
}

// ValidateReport checks a report before it is written, so a bug in the gate never produces a broken contract.
func (s *Schemas) ValidateReport(data []byte) error {
	errs, err := schemaViolations(s.report, data)
	if err != nil {
		return err
	}
	if len(errs) > 0 {
		return fmt.Errorf("report does not match report.v1: %v", errs)
	}
	return nil
}
