package libcore

import (
	"context"
	"testing"
	"time"
)

func TestCloseCancelsBeforeWaitingForStartupLock(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &BoxInstance{ctx: ctx, cancel: cancel}
	b.access.Lock() // Model Start holding the lock during enrollment.
	done := make(chan error, 1)
	go func() { done <- b.CloseTimeout(1000) }()
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		b.access.Unlock()
		<-done
		t.Fatal("close waited for startup before cancelling its I/O")
	}
	select {
	case err := <-done:
		t.Fatalf("teardown bypassed startup lock: %v", err)
	default:
	}
	b.access.Unlock()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("close did not finish after startup released the lock")
	}
}
