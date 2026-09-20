package yandex

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func documentServer(config string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprintf(w, `<html><script id="client-config">%s</script></html>`, config)
	}))
}

func TestDetectLegacyDocument(t *testing.T) {
	server := documentServer(`{"officeActionData":{"editor_config":{},"balancer_url":"https://example.test"}}`)
	defer server.Close()
	transport, err := DetectDocumentTransport(server.URL)
	if err != nil || transport != DocumentTransportLegacy {
		t.Fatalf("transport=%q err=%v", transport, err)
	}
}

func TestDetectModernDocument(t *testing.T) {
	server := documentServer(`{"officeActionData":{"action_url":"https://example.test","access_token":"token"}}`)
	defer server.Close()
	transport, err := DetectDocumentTransport(server.URL)
	if err != nil || transport != DocumentTransportModern {
		t.Fatalf("transport=%q err=%v", transport, err)
	}
}
