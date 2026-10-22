package command

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/hexlabelposition/clud/cli/internal/version"
)

func newVersionCommand() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print the clud version",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			_, err := fmt.Fprintf(cmd.OutOrStdout(), "clud %s\n", version.String())
			return err
		},
	}
}
