package libcore

import "testing"

func TestCheckConfigJSON(t *testing.T) {
	for _, config := range []string{`{}`, `{"outbounds":[{"type":"direct","tag":"direct"}]}`} {
		if err := CheckConfigJSON(config); err != nil {
			t.Fatal(err)
		}
	}
	for _, config := range []string{`{`, `{"outbounds":[{"type":"does-not-exist"}]}`, `{"outbounds":[{"type":"direct","unknown":true}]}`} {
		if err := CheckConfigJSON(config); err == nil {
			t.Fatalf("accepted invalid config: %s", config)
		}
	}
}
