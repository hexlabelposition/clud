package main

import (
	"os"

	"github.com/hexlabelposition/clud/cli/internal/command"
)

func main() {
	if err := command.Execute(); err != nil {
		os.Exit(1)
	}
}
