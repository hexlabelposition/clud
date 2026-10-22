// Package command defines the clud command tree.
package command

import "github.com/spf13/cobra"

// Execute runs the clud command tree. Cobra prints the error to stderr.
func Execute() error {
	return newRootCommand().Execute()
}

func newRootCommand() *cobra.Command {
	root := &cobra.Command{
		Use:   "clud",
		Short: "Command-line client for a Clud server",
		// Show usage only for wrong flags or arguments, not for runtime errors.
		SilenceUsage: true,
	}

	root.AddCommand(newVersionCommand())

	return root
}
