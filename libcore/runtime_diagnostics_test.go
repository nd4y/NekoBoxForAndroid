package libcore

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestRuntimeDiagnosticsBoundsAndOmitsArguments(t *testing.T) {
	var snapshot struct {
		Goroutines string `json:"goroutines"`
		Count      int    `json:"goroutineCount"`
		Omitted    bool   `json:"argumentsOmitted"`
	}
	if err := json.Unmarshal([]byte(RuntimeDiagnostics()), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.Count < 1 || len(snapshot.Goroutines) > 256*1024 || !snapshot.Omitted {
		t.Fatal("invalid bounded snapshot")
	}
	got := omitStackArguments("goroutine 2 [sync.Mutex.Lock]:\npkg.(*Device).Up(secret-value, 0xbeef)\n\tpkg/file.go:42\n")
	if strings.Contains(got, "secret-value") || strings.Contains(got, "0xbeef") || !strings.Contains(got, "pkg.(*Device).Up(...)") || !strings.Contains(got, "file.go:42") {
		t.Fatal("stack arguments not safely omitted")
	}
}
