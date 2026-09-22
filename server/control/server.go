package control

import (
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strings"
)

type Server struct { store *Store; token []byte }
func NewServer(store *Store, token string) *Server { return &Server{store: store, token: []byte(token)} }

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, map[string]any{"ok": true}) })
	mux.HandleFunc("/v1/users", s.users)
	mux.HandleFunc("/v1/users/", s.user)
	return auth(s.token, mux)
}

func auth(token []byte, next http.Handler) http.Handler { return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "); if len(got) != len(token) || subtle.ConstantTimeCompare([]byte(got), token) != 1 { http.Error(w, "unauthorized", http.StatusUnauthorized); return }; next.ServeHTTP(w, r) }) }
func (s *Server) users(w http.ResponseWriter, r *http.Request) { if r.Method == http.MethodGet { writeJSON(w, s.store.List()); return }; if r.Method != http.MethodPost { http.Error(w, "method not allowed", 405); return }; var req struct { Alias string `json:"alias"`; TrafficCap int64 `json:"trafficCap"`; TimeCap int64 `json:"timeCap"`; Profiles []Profile `json:"profiles"` }; if json.NewDecoder(r.Body).Decode(&req) != nil || strings.TrimSpace(req.Alias) == "" { http.Error(w, "invalid request", 400); return }; u, err := s.store.Create(strings.TrimSpace(req.Alias), req.TrafficCap, req.TimeCap, req.Profiles); if err != nil { http.Error(w, "storage error", 500); return }; writeJSON(w, u) }
func (s *Server) user(w http.ResponseWriter, r *http.Request) { id := strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/users/"), "/"); if id == "" { http.Error(w, "missing id", 400); return }; id = strings.TrimSuffix(id, "/revoke"); var err error; switch r.Method { case http.MethodPost: err = s.store.Revoke(id); case http.MethodDelete: err = s.store.Delete(id); default: http.Error(w, "method not allowed", 405); return }; if err != nil { http.Error(w, "user not found", 404); return }; writeJSON(w, map[string]bool{"ok": true}) }
func writeJSON(w http.ResponseWriter, value any) { w.Header().Set("Content-Type", "application/json"); json.NewEncoder(w).Encode(value) }
