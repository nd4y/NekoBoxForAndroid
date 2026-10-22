package libcore

import (
	"errors"
	"os"
	"path/filepath"
	"runtime/debug"
)

// Native panics bypass the Java exception handler. Keep their original Go
// traceback independently of stderr, core logging and cache-log truncation.
func setupNativeCrashOutput(base string, background bool) error {
	directory := filepath.Join(base, "diagnostics")
	if err := os.MkdirAll(directory, 0700); err != nil {
		return err
	}
	name := "crash-native-main.log"
	if background {
		name = "crash-native-bg.log"
	}
	path := filepath.Join(directory, name)
	if info, err := os.Stat(path); err == nil && info.Size() > 0 {
		if err = os.Rename(path, path+".previous"); err != nil {
			return err // Retain the existing evidence if rotation fails.
		}
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	// SetCrashOutput duplicates the descriptor. Closing our copy does not
	// disable capture; the runtime owns it until process exit.
	err = debug.SetCrashOutput(file, debug.CrashOptions{})
	return errors.Join(err, file.Close())
}
