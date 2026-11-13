// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package cloud

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// ErrGameSaveLoginRejected means the service answered 401: the account does not
// exist or the password is wrong. The message is user-facing, so it never
// echoes the password.
var ErrGameSaveLoginRejected = errors.New("account or password is incorrect")

// ErrGameSaveLoginUnsupported is a route-level 404: the service predates the
// account login endpoint (/v1/login, SaveVault 1.9.1).
var ErrGameSaveLoginUnsupported = errors.New("the service does not support account login")

// LoginGameSave trades a user name and password for the tenant's bearer token.
// The password is used once here and never stored; the caller keeps the token
// exactly where the pairing flow keeps a device token.
func LoginGameSave(ctx context.Context, server, username, password string) (string, error) {
	base, err := NormalizeGameSaveURL(server)
	if err != nil {
		return "", err
	}
	if base == "" {
		return "", errors.New("game-save service address must include a host")
	}
	username = strings.TrimSpace(username)
	if username == "" {
		return "", errors.New("enter the account name")
	}
	if password == "" {
		return "", errors.New("enter the password")
	}

	payload, err := json.Marshal(map[string]string{"username": username, "password": password})
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/v1/login", bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", "application/json")
	client := http.Client{Timeout: 20 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return "", fmt.Errorf("cannot reach the game-save service: %w", err)
	}
	defer response.Body.Close()

	switch response.StatusCode {
	case http.StatusOK:
	case http.StatusUnauthorized:
		return "", ErrGameSaveLoginRejected
	case http.StatusNotFound:
		return "", ErrGameSaveLoginUnsupported
	default:
		body, _ := io.ReadAll(io.LimitReader(response.Body, 512))
		return "", fmt.Errorf("game-save login failed (%s): %s", response.Status,
			strings.TrimSpace(string(body)))
	}

	var result struct {
		Token string `json:"token"`
		Vault string `json:"vault"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&result); err != nil {
		return "", fmt.Errorf("cannot read the login response: %w", err)
	}
	if strings.TrimSpace(result.Token) == "" {
		return "", errors.New("the service returned no token")
	}
	return strings.TrimSpace(result.Token), nil
}
