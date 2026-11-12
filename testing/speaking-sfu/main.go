package main

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"github.com/janickiy/meet-space/internal/domain/media"
	"github.com/janickiy/meet-space/internal/infrastructure/sfu"
	pion "github.com/pion/webrtc/v4"
	"io"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"
)

const conference = "00000000-0000-4000-8000-000000000001"
const first = "00000000-0000-4000-8000-000000000011"
const second = "00000000-0000-4000-8000-000000000012"

type envelope struct {
	Version      int       `json:"version"`
	ID           string    `json:"id"`
	ConferenceID string    `json:"conferenceId"`
	Type         string    `json:"type"`
	Timestamp    time.Time `json:"timestamp"`
	ReplyTo      string    `json:"replyTo,omitempty"`
	Data         any       `json:"data"`
}
type requestEnvelope struct {
	ID   string          `json:"id"`
	Type string          `json:"type"`
	Data json.RawMessage `json:"data"`
}
type session struct {
	binding                    media.Binding
	peer                       string
	events                     chan envelope
	microphone, camera, screen bool
	microphoneBlocked          bool
	sequence                   int64
}
type fixture struct {
	mu             sync.Mutex
	sessions       map[string]*session
	manager        *sfu.Manager
	ignorePresence bool
}

func (f *fixture) emit(s *session, kind, reply string, data any) {
	e := envelope{1, uuid.NewString(), conference, kind, time.Now().UTC(), reply, data}
	select {
	case s.events <- e:
	default:
		panic("test event buffer exceeded")
	}
}
func (f *fixture) snapshot(omit bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	people := []any{}
	for _, s := range f.sessions {
		p := map[string]any{"id": s.binding.ParticipantID, "displayName": "Synthetic " + s.binding.ParticipantID[len(s.binding.ParticipantID)-2:], "online": true, "microphoneBlocked": s.microphoneBlocked}
		if !omit {
			p["microphoneEnabled"] = s.microphone
			p["cameraEnabled"] = s.camera
		}
		people = append(people, p)
	}
	for _, s := range f.sessions {
		f.emit(s, "conference.state", "", map[string]any{"connectionId": s.binding.ConnectionID, "participantId": s.binding.ParticipantID, "status": "active", "participants": people})
	}
}
func (f *fixture) session(r *http.Request) *session {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.sessions[r.URL.Query().Get("connectionId")]
}
func respond(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(value)
}
func main() {
	if len(os.Args) != 3 {
		panic("certificate and key arguments required")
	}
	f := &fixture{sessions: map[string]*session{}}
	m, err := sfu.NewManager(sfu.Options{WorkerID: "android-local-speaking", UDPPort: 50377, NATIPs: []string{"10.0.2.2"}, MaxPeers: 4, MaxRooms: 1, NegotiationTimeout: 30 * time.Second, Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Emit: func(b media.Binding, k string, d any) {
		f.mu.Lock()
		s := f.sessions[b.ConnectionID]
		f.mu.Unlock()
		if s != nil {
			f.emit(s, k, "", d)
		}
	}})
	if err != nil {
		panic(err)
	}
	f.manager = m
	defer m.Shutdown(context.Background())
	http.HandleFunc("/open", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("participantId")
		if id != first && id != second {
			http.Error(w, "fixture participant required", 400)
			return
		}
		s := &session{binding: media.Binding{ConferenceID: conference, ParticipantID: id, SessionID: uuid.NewString(), ConnectionID: uuid.NewString(), UserID: id}, events: make(chan envelope, 256), sequence: -1}
		f.mu.Lock()
		for _, old := range f.sessions {
			if old.binding.ParticipantID == id {
				f.mu.Unlock()
				http.Error(w, "already connected", 409)
				return
			}
		}
		f.sessions[s.binding.ConnectionID] = s
		f.mu.Unlock()
		f.snapshot(false)
		respond(w, map[string]any{"connectionId": s.binding.ConnectionID, "participantId": id, "conferenceId": conference, "iceConfiguration": map[string]any{"iceServers": []any{}, "iceTransportPolicy": "all"}})
	})
	http.HandleFunc("/events", func(w http.ResponseWriter, r *http.Request) {
		s := f.session(r)
		if s == nil {
			http.Error(w, "session missing", 404)
			return
		}
		out := []envelope{}
		select {
		case event := <-s.events:
			out = append(out, event)
		case <-time.After(time.Second):
		case <-r.Context().Done():
			return
		}
		for len(out) < 128 {
			select {
			case event := <-s.events:
				out = append(out, event)
			default:
				respond(w, out)
				return
			}
		}
		respond(w, out)
	})
	http.HandleFunc("/send", func(w http.ResponseWriter, r *http.Request) {
		s := f.session(r)
		if s == nil {
			http.Error(w, "session missing", 404)
			return
		}
		var e requestEnvelope
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&e); err != nil {
			http.Error(w, "invalid input", 400)
			return
		}
		var data struct {
			NegotiationID string                 `json:"negotiationId"`
			SDP           string                 `json:"sdp"`
			Publications  []media.Publication    `json:"publications"`
			Candidate     *pion.ICECandidateInit `json:"candidate"`
			TrackID       string                 `json:"trackId"`
		}
		if err := json.Unmarshal(e.Data, &data); err != nil {
			http.Error(w, "invalid data", 400)
			return
		}
		var err error
		switch e.Type {
		case "media.join":
			var view media.PeerView
			view, err = m.Join(r.Context(), s.binding)
			if err == nil {
				s.peer = view.MediaPeerID
				f.emit(s, "media.joined", e.ID, map[string]any{"mediaPeerId": s.peer, "maxPeers": 4, "tracks": m.Tracks(s.peer), "policy": media.ParticipantPolicy{Version: 1}})
			}
		case "media.offer":
			var answer pion.SessionDescription
			answer, err = m.OfferSources(r.Context(), s.peer, data.NegotiationID, data.SDP, data.Publications)
			if err == nil {
				f.emit(s, "media.answer", e.ID, map[string]any{"mediaPeerId": s.peer, "negotiationId": data.NegotiationID, "sdp": answer.SDP})
			}
		case "media.ready":
			err = m.Ready(r.Context(), s.peer, data.NegotiationID)
			if err == nil {
				f.emit(s, "ack", e.ID, map[string]any{"type": "media.ready"})
			}
		case "media.ice":
			err = m.ICE(r.Context(), s.peer, data.Candidate)
		case "media.unpublish":
			err = m.Unpublish(r.Context(), s.peer, data.TrackID)
		case "media.leave":
			err = m.Leave(r.Context(), s.peer)
		default:
			err = fmt.Errorf("unsupported test message %s", e.Type)
		}
		if err != nil {
			fmt.Println("command failed", e.Type, err)
			f.emit(s, "error", e.ID, map[string]any{"code": "fixture_error"})
			http.Error(w, "fixture command rejected", 409)
			return
		}
		respond(w, map[string]bool{"ok": true})
	})
	http.HandleFunc("/presence-mode", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.ignorePresence = r.URL.Query().Get("ignore") == "true"
		f.mu.Unlock()
		respond(w, map[string]bool{"ok": true})
	})
	http.HandleFunc("/presence", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		ignored := f.ignorePresence
		f.mu.Unlock()
		if ignored {
			http.Error(w, "injected presence storage failure", http.StatusServiceUnavailable)
			return
		}
		s := f.session(r)
		if s == nil {
			http.Error(w, "session missing", 404)
			return
		}
		var p struct {
			Sequence   int64 `json:"sequence"`
			Microphone bool  `json:"microphoneEnabled"`
			Camera     bool  `json:"cameraEnabled"`
			Screen     bool  `json:"screenSharing"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			http.Error(w, "invalid", 400)
			return
		}
		f.mu.Lock()
		if p.Sequence > s.sequence {
			s.sequence = p.Sequence
			s.microphone = p.Microphone
			s.camera = p.Camera
			s.screen = p.Screen
			for _, dest := range f.sessions {
				f.emit(dest, "participant.media.updated", "", map[string]any{"participant": map[string]any{"id": s.binding.ParticipantID, "microphoneEnabled": s.microphone, "cameraEnabled": s.camera, "screenSharing": s.screen, "microphoneBlocked": s.microphoneBlocked}})
			}
		}
		f.mu.Unlock()
		respond(w, map[string]bool{"ok": true})
	})

	// Inject explicit signaling independently of SFU forwarding. Keeping old RTP
	// flowing verifies that a confirmed mute/block cannot be undone by statistics.
	http.HandleFunc("/media-state", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		values := r.URL.Query()
		for _, name := range []string{"microphone", "microphoneBlocked"} {
			if value := values.Get(name); value != "" && value != "true" && value != "false" {
				http.Error(w, "invalid boolean", 400)
				return
			}
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		var selected *session
		for _, s := range f.sessions {
			if s.binding.ParticipantID == values.Get("participantId") {
				selected = s
				break
			}
		}
		if selected == nil {
			http.Error(w, "participant missing", 404)
			return
		}
		if value := values.Get("microphone"); value != "" {
			selected.microphone = value == "true"
		}
		if value := values.Get("microphoneBlocked"); value != "" {
			selected.microphoneBlocked = value == "true"
		}
		for _, dest := range f.sessions {
			f.emit(dest, "participant.media.updated", "", map[string]any{"participant": map[string]any{
				"id": selected.binding.ParticipantID, "microphoneEnabled": selected.microphone,
				"cameraEnabled": selected.camera, "screenSharing": selected.screen,
				"microphoneBlocked": selected.microphoneBlocked,
			}})
		}
		respond(w, map[string]bool{"ok": true})
	})
	http.HandleFunc("/snapshot", func(w http.ResponseWriter, r *http.Request) {
		f.snapshot(r.URL.Query().Get("omitMicrophone") == "true")
		respond(w, map[string]bool{"ok": true})
	})
	http.HandleFunc("/close", func(w http.ResponseWriter, r *http.Request) {
		s := f.session(r)
		if s == nil {
			respond(w, map[string]bool{"ok": true})
			return
		}
		f.mu.Lock()
		delete(f.sessions, s.binding.ConnectionID)
		f.mu.Unlock()
		if s.peer != "" {
			m.Leave(context.Background(), s.peer)
		}
		f.snapshot(false)
		respond(w, map[string]bool{"ok": true})
	})
	http.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) { respond(w, m.Snapshot()) })
	fmt.Println("Isolated actual backend SFU ready at https://127.0.0.1:18477")
	srv := &http.Server{Addr: "127.0.0.1:18477", ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second}
	if err := srv.ListenAndServeTLS(os.Args[1], os.Args[2]); err != nil {
		panic(err)
	}
}
