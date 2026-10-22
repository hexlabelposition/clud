// Package version reports the version of the clud binary.
package version

// Overridden for releases at build time:
//
//	-ldflags "-X github.com/hexlabelposition/clud/cli/internal/version.version=0.1.0"
var version = "0.0.0"

// String returns the version of the binary.
func String() string {
	return version
}
