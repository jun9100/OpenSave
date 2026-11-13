package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
	"github.com/opensave/opensave/internal/version"
)

// normalizePairingTarget applies the same address rules as the provider and
// returns the host the user must confirm before a claim is sent.
func normalizePairingTarget(raw string) (server, host string, err error) {
	server, err = cloud.NormalizeGameSaveURL(raw)
	if err != nil {
		return "", "", err
	}
	if server == "" {
		return "", "", errors.New("game-save service address must include a host")
	}
	parsed, err := url.Parse(server)
	if err != nil || parsed.Host == "" {
		return "", "", errors.New("game-save service address must include a host")
	}
	return server, parsed.Host, nil
}

// handleCloudPairPreview parses a pasted link or separate fields without
// touching the network, so the UI can show the host before anything is sent.
func (s *Server) handleCloudPairPreview(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Link   string `json:"link"`
		Server string `json:"server"`
		Code   string `json:"code"`
	}
	if err := readJSON(r, &body); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	var server, host, code string
	var err error
	if strings.TrimSpace(body.Link) != "" {
		server, code, err = cloud.ParsePairLink(body.Link)
		if err == nil {
			_, host, err = normalizePairingTarget(server)
		}
	} else {
		server, host, err = normalizePairingTarget(body.Server)
		if err == nil {
			code, err = cloud.NormalizePairingCode(body.Code)
		}
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"server": server,
		"host":   host,
		"code":   cloud.FormatPairingCode(code),
	})
}

// handleCloudPairClaim exchanges the confirmed pairing code for a device
// token and stores it as the game-save sync key. The token is never echoed.
func (s *Server) handleCloudPairClaim(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Server        string `json:"server"`
		Code          string `json:"code"`
		ConfirmedHost string `json:"confirmedHost"`
	}
	if err := readJSON(r, &body); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	server, host, err := normalizePairingTarget(body.Server)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	code, err := cloud.NormalizePairingCode(body.Code)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if !strings.EqualFold(strings.TrimSpace(body.ConfirmedHost), host) {
		writeError(w, http.StatusBadRequest, "confirm the server's host name before pairing")
		return
	}

	settings, err := s.Daemon.Store.GetSettings()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	deviceID := strings.TrimSpace(settings.NodeID)
	if deviceID == "" {
		writeError(w, http.StatusInternalServerError, "this device has no ID yet")
		return
	}
	deviceName := strings.TrimSpace(settings.DeviceName)
	if deviceName == "" {
		deviceName = "OpenSave " + runtime.GOOS
	}

	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	result, err := s.Daemon.Cloud.ClaimPairing(ctx, server, code, cloud.PairDevice{
		DeviceID:   deviceID,
		DeviceName: deviceName,
		Platform:   "opensave-" + runtime.GOOS,
		Version:    version.Version,
	})
	if err != nil {
		status := http.StatusBadGateway
		switch {
		case errors.Is(err, cloud.ErrPairingUnsupported):
			status = http.StatusNotImplemented
		case errors.Is(err, cloud.ErrPairingInvalid):
			status = http.StatusBadRequest
		case errors.Is(err, cloud.ErrPairingRateLimited):
			status = http.StatusTooManyRequests
		}
		writeError(w, status, err.Error())
		return
	}

	cfg, err := s.Daemon.Store.GetCloudConfig()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	cfg.Provider = cloud.GameSaveProvider
	cfg.URL = server
	cfg.Password = result.Token
	cfg.Enabled = true
	if err := s.Daemon.Store.UpdateCloudConfig(cfg); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	// A successful pairing supersedes any refusal from the previous key.
	if err := s.Daemon.Store.SetGameSaveAuthBlock(""); err != nil && s.Daemon.Log != nil {
		s.Daemon.Log.Log("warn", "could not clear the game-save auth block after pairing: "+err.Error())
	}
	if err := s.Daemon.Store.SetGameSaveAuthWarning(store.GameSaveAuthWarningNone); err != nil && s.Daemon.Log != nil {
		s.Daemon.Log.Log("warn", "could not clear the game-save auth warning after pairing: "+err.Error())
	}
	if s.Daemon.Log != nil {
		s.Daemon.Log.Log("info", fmt.Sprintf("paired with game-save at %s as %q (device token %s)",
			host, result.Name, result.TokenID))
	}

	writeJSON(w, http.StatusOK, struct {
		Name    string `json:"name"`
		TokenID string `json:"tokenId"`
		Server  string `json:"server"`
	}{Name: result.Name, TokenID: result.TokenID, Server: server})
}

type cloudAuthStatus struct {
	State           string `json:"state"`
	Name            string `json:"name"`
	TokenID         string `json:"tokenId"`
	SharedTokenMode string `json:"sharedTokenMode"`
	Warning         bool   `json:"warning"`
	Error           string `json:"error"`
}

// handleCloudAuthStatus reports which credential game-save accepts. A locally
// recorded refusal is answered without a request, so a revoked device stays
// quiet until the user pairs again.
func (s *Server) handleCloudAuthStatus(w http.ResponseWriter, r *http.Request) {
	cfg, err := s.Daemon.Store.GetCloudConfig()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	status := cloudAuthStatus{State: "off"}
	if cfg.Provider != cloud.GameSaveProvider || strings.TrimSpace(cfg.URL) == "" || strings.TrimSpace(cfg.Password) == "" {
		writeJSON(w, http.StatusOK, status)
		return
	}
	switch cfg.GameSaveAuthBlock {
	case "token_revoked":
		status.State = "revoked"
	case "shared_token_disabled":
		status.State = "sharedDisabled"
	default:
		who, err := s.Daemon.Cloud.GameSaveWhoami()
		switch {
		case errors.Is(err, cloud.ErrGameSaveAuthOff):
			status.State = "off"
		case errors.Is(err, cloud.ErrGameSaveWhoamiUnsupported):
			status.State = "unsupported"
		case err != nil:
			var blocked *cloud.GameSaveAuthBlockedError
			if errors.As(err, &blocked) {
				switch blocked.Code {
				case "token_revoked":
					status.State = "revoked"
				case "shared_token_disabled":
					status.State = "sharedDisabled"
				default:
					status.State = "unauthorized"
				}
			} else {
				status.State = "error"
				status.Error = err.Error()
			}
		default:
			status.Name = who.Name
			status.TokenID = who.TokenID
			status.SharedTokenMode = who.SharedTokenMode
			switch who.Auth {
			case "device":
				status.State = "device"
			case "shared":
				status.State = "shared"
			default:
				status.State = "error"
				status.Error = "the service returned an unknown authentication type"
			}
		}
	}

	// whoami may itself have marked the notice pending from the warning
	// header, so use the row as it is now rather than the copy read above.
	if latest, err := s.Daemon.Store.GetCloudConfig(); err == nil {
		cfg.GameSaveAuthWarning = latest.GameSaveAuthWarning
	}
	status.Warning = cfg.GameSaveAuthWarning == store.GameSaveAuthWarningPending ||
		(status.State == "shared" && status.SharedTokenMode == "warn" &&
			cfg.GameSaveAuthWarning != store.GameSaveAuthWarningDismissed)
	writeJSON(w, http.StatusOK, status)
}

// handleCloudAuthWarningDismiss hides the shared-token deprecation notice
// until the next address or sync-key change. There is no request body.
func (s *Server) handleCloudAuthWarningDismiss(w http.ResponseWriter, r *http.Request) {
	if err := s.Daemon.Store.SetGameSaveAuthWarning(store.GameSaveAuthWarningDismissed); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{})
}

// handleCloudLogin exchanges an account name and password for the tenant's
// token and stores it exactly where a paired device token lives, so every
// existing game-save call keeps working unchanged.
func (s *Server) handleCloudLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Server        string `json:"server"`
		Username      string `json:"username"`
		Password      string `json:"password"`
		ConfirmedHost string `json:"confirmedHost"`
	}
	if err := readJSON(r, &body); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	server, host, err := normalizePairingTarget(body.Server)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if confirmed := strings.TrimSpace(body.ConfirmedHost); confirmed != "" &&
		!strings.EqualFold(confirmed, host) {
		writeError(w, http.StatusBadRequest, "confirm the server's host name before signing in")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	token, err := cloud.LoginGameSave(ctx, server, body.Username, body.Password)
	if err != nil {
		status := http.StatusBadGateway
		switch {
		case errors.Is(err, cloud.ErrGameSaveLoginRejected):
			status = http.StatusUnauthorized
		case errors.Is(err, cloud.ErrGameSaveLoginUnsupported):
			status = http.StatusNotImplemented
		}
		writeError(w, status, err.Error())
		return
	}

	cfg, err := s.Daemon.Store.GetCloudConfig()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	cfg.Provider = cloud.GameSaveProvider
	cfg.URL = server
	cfg.Password = token
	cfg.Enabled = true
	if err := s.Daemon.Store.UpdateCloudConfig(cfg); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := s.Daemon.Store.SetGameSaveAuthBlock(""); err != nil && s.Daemon.Log != nil {
		s.Daemon.Log.Log("warn", "could not clear the game-save auth block after login: "+err.Error())
	}
	if s.Daemon.Log != nil {
		s.Daemon.Log.Log("info", fmt.Sprintf("signed in to game-save at %s as %q", host, strings.TrimSpace(body.Username)))
	}
	writeJSON(w, http.StatusOK, map[string]string{"server": server, "username": strings.TrimSpace(body.Username)})
}
