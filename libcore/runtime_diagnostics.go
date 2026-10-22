package libcore

import (
	"encoding/json"
	"runtime"
	"strings"
)

// RuntimeDiagnostics observes even a blocked native core, without starting a
// profiler, native box, connection or second peer identity. Argument values are
// deliberately removed before the stack leaves the core.
func RuntimeDiagnostics() string {
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	buffer := make([]byte, 256*1024)
	n := runtime.Stack(buffer, true)
	result, _ := json.Marshal(map[string]any{
		"goVersion": runtime.Version(), "goroutineCount": runtime.NumGoroutine(),
		"heapAllocBytes": memory.HeapAlloc, "heapSysBytes": memory.HeapSys, "sysBytes": memory.Sys,
		"numGC": memory.NumGC, "goroutines": omitStackArguments(string(buffer[:n])),
		"truncated": n == len(buffer), "argumentsOmitted": true,
	})
	return string(result)
}

func omitStackArguments(stack string) string {
	lines := strings.Split(stack, "\n")
	for i, line := range lines {
		if strings.HasPrefix(line, "\t") || !strings.HasSuffix(line, ")") {
			continue
		}
		if start := strings.LastIndex(line, "("); start >= 0 {
			lines[i] = line[:start+1] + "...)"
		}
	}
	return strings.Join(lines, "\n")
}
