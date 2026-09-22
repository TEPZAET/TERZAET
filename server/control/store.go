package control

import (
	"crypto/rand"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type User struct {
	ID          string    `json:"id"`
	Alias       string    `json:"alias"`
	TrafficCap  int64     `json:"trafficCap"`
	TimeCap     int64     `json:"timeCap"`
	CreatedAt   time.Time `json:"createdAt"`
	RevokedAt   time.Time `json:"revokedAt,omitempty"`
	Bundle      string    `json:"bundle"`
}

type Profile struct {
	Name string `json:"name"`
	URI  string `json:"uri"`
}

type Bundle struct {
	Version  int       `json:"v"`
	UserID   string    `json:"u"`
	Profiles []Profile `json:"p"`
	Expires  int64     `json:"e,omitempty"`
}

type storeFile struct {
	Users []User `json:"users"`
}

type Store struct {
	mu       sync.RWMutex
	path     string
	secret   []byte
	users    []User
}

func Open(path, secretPath string) (*Store, error) {
	secret, err := loadSecret(secretPath)
	if err != nil { return nil, err }
	s := &Store{path: path, secret: secret}
	if data, readErr := os.ReadFile(path); readErr == nil {
		var saved storeFile
		if json.Unmarshal(data, &saved) == nil { s.users = saved.Users }
	} else if !errors.Is(readErr, os.ErrNotExist) { return nil, readErr }
	return s, nil
}

func loadSecret(path string) ([]byte, error) {
	if data, err := os.ReadFile(path); err == nil && len(data) >= 32 { return data, nil }
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil { return nil, err }
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil { return nil, err }
	if err := os.WriteFile(path, secret, 0600); err != nil { return nil, err }
	return secret, nil
}

func (s *Store) saveLocked() error {
	data, err := json.MarshalIndent(storeFile{Users: s.users}, "", "  ")
	if err != nil { return err }
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil { return err }
	return os.Rename(tmp, s.path)
}

func (s *Store) List() []User { s.mu.RLock(); defer s.mu.RUnlock(); return append([]User(nil), s.users...) }

func (s *Store) Create(alias string, trafficCap, timeCap int64, profiles []Profile) (User, error) {
	s.mu.Lock(); defer s.mu.Unlock()
	idBytes := make([]byte, 10)
	if _, err := rand.Read(idBytes); err != nil { return User{}, err }
	id := hex.EncodeToString(idBytes)
	bundle := Bundle{Version: 1, UserID: id, Profiles: profiles}
	payload, err := json.Marshal(bundle); if err != nil { return User{}, err }
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	hash := hmac.New(sha256.New, s.secret)
	hash.Write(payload)
	bundleToken := "terzaet://" + encoded + "." + base64.RawURLEncoding.EncodeToString(hash.Sum(nil))
	u := User{ID: id, Alias: alias, TrafficCap: trafficCap, TimeCap: timeCap, CreatedAt: time.Now().UTC(), Bundle: bundleToken}
	s.users = append(s.users, u)
	return u, s.saveLocked()
}

func (s *Store) Revoke(id string) error { s.mu.Lock(); defer s.mu.Unlock(); for i := range s.users { if s.users[i].ID == id { s.users[i].RevokedAt = time.Now().UTC(); return s.saveLocked() } }; return os.ErrNotExist }
func (s *Store) Delete(id string) error { s.mu.Lock(); defer s.mu.Unlock(); for i := range s.users { if s.users[i].ID == id { s.users = append(s.users[:i], s.users[i+1:]...); return s.saveLocked() } }; return os.ErrNotExist }
