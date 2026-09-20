package yandex

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

const (
	DocumentTransportLegacy = "yandex"
	DocumentTransportModern = "vyandex"
)

func DetectDocumentTransport(docURL string) (string, error) {
	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodGet, docURL, nil)
	if err != nil {
		return "", fmt.Errorf("document URL: %w", err)
	}
	req.Header.Set("User-Agent", "Mozilla/5.0")
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("open document: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return "", fmt.Errorf("open document: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return "", fmt.Errorf("read document: %w", err)
	}
	match := clientConfigRe.FindSubmatch(body)
	if len(match) < 2 {
		return "", fmt.Errorf("document editor configuration not found; check public access")
	}
	var config map[string]interface{}
	if err := json.Unmarshal(match[1], &config); err != nil {
		return "", fmt.Errorf("parse document configuration: %w", err)
	}
	office, _ := config["officeActionData"].(map[string]interface{})
	if office == nil {
		return "", fmt.Errorf("document editor is unavailable")
	}
	if value, ok := office["action_url"].(string); ok && value != "" {
		if token, ok := office["access_token"].(string); ok && token != "" {
			return DocumentTransportModern, nil
		}
	}
	if editor, ok := office["editor_config"].(map[string]interface{}); ok && editor != nil {
		if balancer, ok := office["balancer_url"].(string); ok && balancer != "" {
			return DocumentTransportLegacy, nil
		}
	}
	return "", fmt.Errorf("unsupported Yandex document editor format")
}
