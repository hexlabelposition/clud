package command

import (
	"bytes"
	"strings"
	"testing"
)

func TestVersion(t *testing.T) {
	var out bytes.Buffer
	root := newRootCommand()
	root.SetOut(&out)
	root.SetArgs([]string{"version"})

	if err := root.Execute(); err != nil {
		t.Fatalf("version: %v", err)
	}

	if got := out.String(); !strings.HasPrefix(got, "clud ") || !strings.HasSuffix(got, "\n") {
		t.Errorf("version output = %q, want \"clud <version>\\n\"", got)
	}
}
