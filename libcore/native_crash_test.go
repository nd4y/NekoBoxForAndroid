package libcore

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestNativeCrashOutputChild(t *testing.T) {
	base := os.Getenv("NEKO_TEST_NATIVE_CRASH_DIR")
	if base == "" {
		return
	}
	if err := setupNativeCrashOutput(base, os.Getenv("NEKO_TEST_NATIVE_CRASH_MAIN") != "1"); err != nil {
		t.Fatal(err)
	}
	if message := os.Getenv("NEKO_TEST_NATIVE_CRASH_MESSAGE"); message != "" {
		go func() { panic(message) }()
		select {}
	}
}

func TestNativeCrashOutputSurvivesRestartWithoutCoreLogging(t *testing.T) {
	base := t.TempDir()
	run := func(main bool, message string) {
		t.Helper()
		command := exec.Command(os.Args[0], "-test.run=^TestNativeCrashOutputChild$")
		command.Env = append(os.Environ(), "NEKO_TEST_NATIVE_CRASH_DIR="+base,
			"NEKO_TEST_NATIVE_CRASH_MESSAGE="+message)
		if main {
			command.Env = append(command.Env, "NEKO_TEST_NATIVE_CRASH_MAIN=1")
		}
		err := command.Run() // stdout/stderr are discarded, as with logging disabled.
		if (err != nil) != (message != "") {
			t.Fatalf("unexpected child outcome: %v", err)
		}
	}
	read := func(name string) string {
		t.Helper()
		data, err := os.ReadFile(filepath.Join(base, "diagnostics", name))
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}
	run(false, "native-crash-first")
	first := read("crash-native-bg.log")
	if !strings.Contains(first, "panic: native-crash-first") || !strings.Contains(first, "native_crash_test.go:") {
		t.Fatal("original panic and Go frame were not captured")
	}
	run(false, "native-crash-second")
	if read("crash-native-bg.log.previous") != first {
		t.Fatal("restart lost the previous native traceback")
	}
	second := read("crash-native-bg.log")
	if !strings.Contains(second, "panic: native-crash-second") {
		t.Fatal("runtime-owned descriptor did not survive caller Close")
	}
	run(false, "")
	if read("crash-native-bg.log.previous") != second || read("crash-native-bg.log") != "" {
		t.Fatal("normal restart did not preserve the last crash")
	}
	run(true, "native-crash-main")
	if !strings.Contains(read("crash-native-main.log"), "panic: native-crash-main") || read("crash-native-bg.log.previous") != second {
		t.Fatal("process crash files are not independent")
	}
}
