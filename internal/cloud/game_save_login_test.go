// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package cloud

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestLoginGameSaveReturnsTheTenantToken(t *testing.T) {
	var got map[string]string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/login" || r.Method != http.MethodPost {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		_ = json.NewDecoder(r.Body).Decode(&got)
		_ = json.NewEncoder(w).Encode(map[string]string{"token": "tok-1", "vault": "alice"})
	}))
	defer server.Close()

	token, err := LoginGameSave(context.Background(), server.URL, " alice ", "s3cret-pass")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if token != "tok-1" {
		t.Fatalf("token = %q", token)
	}
	if got["username"] != "alice" || got["password"] != "s3cret-pass" {
		t.Fatalf("request body = %v", got)
	}
}

func TestLoginGameSaveReportsARejection(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"invalid credentials"}`))
	}))
	defer server.Close()

	_, err := LoginGameSave(context.Background(), server.URL, "alice", "wrong")
	if !errors.Is(err, ErrGameSaveLoginRejected) {
		t.Fatalf("err = %v", err)
	}
}

func TestLoginGameSaveReportsAnOldService(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	_, err := LoginGameSave(context.Background(), server.URL, "alice", "s3cret-pass")
	if !errors.Is(err, ErrGameSaveLoginUnsupported) {
		t.Fatalf("err = %v", err)
	}
}

func TestLoginGameSaveRejectsAnEmptyToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"vault":"alice"}`))
	}))
	defer server.Close()

	if _, err := LoginGameSave(context.Background(), server.URL, "alice", "s3cret-pass"); err == nil {
		t.Fatal("an empty token must be refused")
	}
}
