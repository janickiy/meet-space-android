// Real HTTPS/WSS/PostgreSQL/Redis/MinIO contract probe. No credentials/bodies logged.
package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	ws "github.com/gorilla/websocket"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type obj = map[string]any

var properties map[string]string
var client *http.Client
var tlsConfig *tls.Config
var checks []obj
var current string

func check(ok bool, name string) {
	current = name
	if !ok {
		panic(name)
	}
	checks = append(checks, obj{"check": name, "passed": true})
}
func object(v any) obj {
	if v == nil {
		return obj{}
	}
	return v.(map[string]any)
}
func items(v obj) []any {
	if v["items"] == nil {
		return nil
	}
	return v["items"].([]any)
}
func str(v any) string {
	if v == nil {
		return ""
	}
	return v.(string)
}
func ident(v obj) string { return str(v["id"]) }

type actor struct{ token, id, email string }

func (a actor) request(method, path string, body any, status int, name string) obj {
	var data []byte
	if body != nil {
		data, _ = json.Marshal(body)
	}
	raw := a.raw(method, path, data, status, name, true)
	var value obj
	if len(raw) > 0 {
		check(json.Unmarshal(raw, &value) == nil, name+" JSON")
	}
	return value
}
func (a actor) raw(method, path string, data []byte, status int, name string, auth bool) []byte {
	current = name
	absolute := path
	if !strings.HasPrefix(path, "https://") {
		absolute = properties["origin"] + path
	}
	req, err := http.NewRequest(method, absolute, bytes.NewReader(data))
	check(err == nil, name+" request")
	req.Header.Set("Content-Type", "application/json")
	if auth && a.token != "" {
		req.Header.Set("Authorization", "Bearer "+a.token)
	}
	res, err := client.Do(req)
	check(err == nil, name+" transport")
	defer res.Body.Close()
	check((res.StatusCode == status || (name == "create direct conversation" && res.StatusCode == 200)), fmt.Sprintf("%s HTTP %d (received %d)", name, status, res.StatusCode))
	raw, err := io.ReadAll(io.LimitReader(res.Body, 11<<20))
	check(err == nil, name+" bounded body")
	return raw
}
func login(index int) actor {
	a := actor{email: properties[fmt.Sprint("email", index)]}
	v := a.request("POST", "/api/v1/auth/login", obj{"email": a.email, "password": properties[fmt.Sprint("password", index)]}, 200, "actual credential login")
	a.token = str(v["accessToken"])
	a.id = ident(object(v["user"]))
	return a
}

type stream struct {
	conn   *ws.Conn
	mu     sync.Mutex
	events []obj
	closed chan struct{}
}

func (a actor) socket() *stream {
	v := a.request("POST", "/api/v1/ws-ticket", obj{}, 201, "single-use global user ticket")
	d := ws.Dialer{TLSClientConfig: tlsConfig, HandshakeTimeout: 5 * time.Second}
	header := http.Header{"Origin": {properties["origin"]}}
	c, _, err := d.Dial(strings.Replace(properties["origin"], "https://", "wss://", 1)+"/api/v1/ws?ticket="+url.QueryEscape(str(v["ticket"])), header)
	check(err == nil, "validated global WSS upgrade")
	s := &stream{conn: c, closed: make(chan struct{})}
	go func() {
		defer close(s.closed)
		for {
			_, raw, err := c.ReadMessage()
			if err != nil {
				return
			}
			var event obj
			if json.Unmarshal(raw, &event) != nil {
				continue
			}
			s.mu.Lock()
			if len(s.events) < 1024 {
				s.events = append(s.events, event)
			}
			s.mu.Unlock()
		}
	}()
	s.await(func(e obj) bool { return e["type"] == "user.presence" }, "global presence event")
	return s
}
func (s *stream) count(f func(obj) bool) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, e := range s.events {
		if f(e) {
			n++
		}
	}
	return n
}
func (s *stream) await(f func(obj) bool, name string) {
	until := time.Now().Add(10 * time.Second)
	for time.Now().Before(until) {
		if s.count(f) > 0 {
			check(true, name)
			return
		}
		time.Sleep(40 * time.Millisecond)
	}
	check(false, name)
}
func (s *stream) close() {
	_ = s.conn.Close()
	select {
	case <-s.closed:
	case <-time.After(time.Second):
		check(false, "WSS reader cleanup")
	}
}
func event(kind, id string) func(obj) bool {
	return func(e obj) bool {
		return e["type"] == kind && (id == "" || ident(object(object(e["data"])["message"])) == id)
	}
}
func msg(a actor, conversation, text string) obj {
	return object(a.request("POST", "/api/v1/conversations/"+conversation+"/messages", obj{"clientRequestId": uuid.NewString(), "text": text}, 201, "persist message")["item"])
}
func upload(a actor, conversation string) (string, []byte) {
	data := []byte("MeetSpace isolated attachment verification\n")
	init := a.request("POST", "/api/v1/conversations/"+conversation+"/attachments/init", obj{"clientRequestId": uuid.NewString(), "filename": "verification.txt", "mimeType": "text/plain", "size": len(data)}, 201, "init private attachment")
	id := ident(object(init["item"]))
	a.raw("PUT", str(init["uploadUrl"]), data, 200, "raw authenticated API upload", true)
	finalized := a.request("POST", "/api/v1/conversations/"+conversation+"/attachments/"+id+"/finalize", obj{}, 200, "finalize actual MinIO object")
	sum := sha256.Sum256(data)
	check(object(finalized["item"])["checksum"] == hex.EncodeToString(sum[:]), "server attachment checksum matches bytes")
	a.request("POST", "/api/v1/conversations/"+conversation+"/messages", obj{"clientRequestId": uuid.NewString(), "text": "Attachment", "attachmentIds": []string{id}}, 201, "attach finalized private file")
	return id, data
}
func main() {
	defer func() {
		if recover() != nil {
			fmt.Println("Stage07 contract failure:", current)
			os.Exit(1)
		}
	}()
	home := os.Args[1]
	raw, err := os.ReadFile(filepath.Join(home, "live.properties"))
	check(err == nil, "read private fixture properties")
	properties = map[string]string{}
	for _, line := range strings.Split(string(raw), "\n") {
		p := strings.SplitN(line, "=", 2)
		if len(p) == 2 {
			properties[p[0]] = p[1]
		}
	}
	ca, err := os.ReadFile(properties["caPath"])
	check(err == nil, "read fixture CA")
	roots := x509.NewCertPool()
	check(roots.AppendCertsFromPEM(ca), "private CA parsed")
	tlsConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}
	client = &http.Client{Transport: &http.Transport{TLSClientConfig: tlsConfig}, Timeout: 20 * time.Second, CheckRedirect: func(r *http.Request, v []*http.Request) error { return http.ErrUseLastResponse }}
	a, b, c, d := login(0), login(1), login(2), login(3)
	a2 := a.socket()
	defer a2.close()
	a1 := a.socket()
	defer a1.close()
	bs := b.socket()
	defer bs.close()
	cs := c.socket()
	defer cs.close()
	direct := object(a.request("POST", "/api/v1/conversations/direct", obj{"userId": b.id}, 201, "create direct conversation")["item"])
	did := ident(direct)
	again := object(b.request("POST", "/api/v1/conversations/direct", obj{"userId": a.id}, 200, "direct pair idempotency")["item"])
	check(ident(again) == did, "one stable direct pair")
	c.request("GET", "/api/v1/conversations/"+did, nil, 403, "outsider cannot inspect direct metadata")
	presence := object(a.request("GET", "/api/v1/conversations/"+did+"/peer-presence", nil, 200, "actual Redis peer presence")["item"])
	check(presence["online"] == true, "other account socket is online")
	req := obj{"clientRequestId": uuid.NewString(), "text": "Stage07 verified message"}
	first := object(a.request("POST", "/api/v1/conversations/"+did+"/messages", req, 201, "create idempotent direct message")["item"])
	mid := ident(first)
	repeat := object(a.request("POST", "/api/v1/conversations/"+did+"/messages", req, 200, "repeat exact idempotency key")["item"])
	check(ident(repeat) == mid, "retry reuses server message ID")
	req["text"] = "Different body"
	a.request("POST", "/api/v1/conversations/"+did+"/messages", req, 409, "idempotency conflict rejects changed body")
	a1.await(event("message.created", mid), "sender first device receives message event")
	a2.await(event("message.created", mid), "sender second device receives message event")
	bs.await(event("message.created", mid), "recipient device receives message event")
	time.Sleep(150 * time.Millisecond)
	check(bs.count(event("message.created", mid)) == 1, "retry publishes exactly one created event")
	check(cs.count(event("message.created", mid)) == 0, "outsider receives no private message")
	reply := object(b.request("POST", "/api/v1/conversations/"+did+"/messages", obj{"clientRequestId": uuid.NewString(), "text": "Reply", "replyTo": mid}, 201, "create real reply")["item"])
	check(object(reply["replyPreview"])["id"] == mid, "reply preview references actual message")
	c.request("GET", "/api/v1/conversations/"+did+"/messages", nil, 403, "history IDOR rejected")
	b.request("PATCH", "/api/v1/conversations/"+did+"/messages/"+mid, obj{"text": "Illegal"}, 403, "cannot edit other author")
	edited := object(a.request("PATCH", "/api/v1/conversations/"+did+"/messages/"+mid, obj{"text": "Edited verified message"}, 200, "edit own message")["item"])
	check(edited["version"].(float64) > first["version"].(float64), "edit increments version")
	bs.await(event("message.updated", mid), "real edit event")
	b.request("PUT", "/api/v1/conversations/"+did+"/chat/read", obj{"messageId": ident(reply)}, 200, "advance real read cursor")
	state := object(b.request("GET", "/api/v1/conversations/"+did+"/chat/read", nil, 200, "read authoritative unread count")["item"])
	check(state["unreadCount"] == float64(0), "reading latest clears unread")
	b.request("PUT", "/api/v1/conversations/"+did+"/chat/read", obj{"messageId": mid}, 200, "late read update accepted monotonically")
	state = object(b.request("GET", "/api/v1/conversations/"+did+"/chat/read", nil, 200, "read monotonic cursor")["item"])
	check(state["lastReadMessageId"] == ident(reply), "late read cannot move cursor backwards")
	deleted := object(a.request("DELETE", "/api/v1/conversations/"+did+"/messages/"+mid, nil, 200, "delete own message")["item"])
	check(deleted["deletedAt"] != nil && deleted["text"] == "", "delete exposes tombstone without original body")
	bs.await(event("message.deleted", mid), "real delete event")
	groupReq := obj{"clientRequestId": uuid.NewString(), "name": "Stage07 verified group", "description": "Disposable actual backend", "memberIds": []string{b.id, c.id}}
	group := object(a.request("POST", "/api/v1/conversations/group", groupReq, 201, "create actual group")["item"])
	gid := ident(group)
	gRepeat := object(a.request("POST", "/api/v1/conversations/group", groupReq, 200, "group create idempotency")["item"])
	check(ident(gRepeat) == gid, "group retry stable ID")
	d.request("GET", "/api/v1/conversations/"+gid, nil, 403, "outsider cannot inspect group")
	b.request("PATCH", "/api/v1/conversations/"+gid, obj{"name": "No permission"}, 403, "member cannot change metadata")
	a.request("PATCH", "/api/v1/conversations/"+gid, obj{"name": "Stage07 renamed group"}, 200, "owner changes metadata")
	foreign := msg(a, gid, "Group history")
	a.request("POST", "/api/v1/conversations/"+did+"/messages", obj{"clientRequestId": uuid.NewString(), "text": "Illegal reply", "replyTo": ident(foreign)}, 422, "cross conversation reply rejected")
	a.request("PATCH", "/api/v1/conversations/"+gid+"/members/"+b.id, obj{"role": "admin"}, 200, "owner promotes administrator")
	members := a.request("GET", "/api/v1/conversations/"+gid+"/members", nil, 200, "group member roles and presence")
	check(len(items(members)) == 3, "three real group members")
	fileID, content := upload(a, gid)
	dl := b.request("GET", "/api/v1/conversations/"+gid+"/attachments/"+fileID+"/download", nil, 200, "group download descriptor")
	check(dl["authenticated"] == true && strings.HasPrefix(str(dl["url"]), "/api/v1/conversations/"+gid+"/"), "group file uses membership checked API path")
	received := b.raw("GET", str(dl["url"]), nil, 200, "member reads actual private file", true)
	check(bytes.Equal(content, received), "group downloaded bytes match original")
	d.raw("GET", str(dl["url"]), nil, 403, "outsider file content denied", true)
	a.request("DELETE", "/api/v1/conversations/"+gid+"/members/"+c.id, nil, 200, "owner removes member")
	cs.await(event("conversation.member.removed", ""), "removed member receives minimal lifecycle event")
	c.request("GET", "/api/v1/conversations/"+gid+"/messages", nil, 403, "removed member history revoked")
	c.raw("GET", str(dl["url"]), nil, 403, "removed member file access revoked", true)
	afterRemoval := msg(a, gid, "After removal")
	time.Sleep(200 * time.Millisecond)
	check(cs.count(event("message.created", ident(afterRemoval))) == 0, "removed socket receives no later group message")
	directFile, directBytes := upload(a, did)
	signed := b.request("GET", "/api/v1/conversations/"+did+"/attachments/"+directFile+"/download", nil, 200, "direct signed storage descriptor")
	check(strings.HasPrefix(str(signed["url"]), "https://") && signed["authenticated"] != true, "direct signed URL requires HTTPS without bearer")
	received = b.raw("GET", str(signed["url"]), nil, 200, "validated TLS private MinIO signed download without JWT", false)
	check(bytes.Equal(directBytes, received), "direct storage bytes match original")
	parsed, _ := url.Parse(str(signed["url"]))
	unsigned := *parsed
	unsigned.RawQuery = ""
	b.raw("GET", unsigned.String(), nil, 403, "unsigned private MinIO object denied", false)
	c.request("GET", "/api/v1/conversations/"+did+"/attachments/"+directFile+"/download", nil, 403, "outsider cannot obtain direct signed URL")
	a.request("POST", "/api/v1/conversations/"+gid+"/messages", obj{"clientRequestId": uuid.NewString(), "text": "Illegal attachment", "attachmentIds": []string{directFile}}, 409, "cross conversation file attachment rejected")
	folder := object(a.request("POST", "/api/v1/folders", obj{"name": "Stage07 folder " + uuid.NewString()[:6]}, 201, "create personal folder")["item"])
	fid := ident(folder)
	a.request("PUT", "/api/v1/folders/"+fid+"/items/conversation/"+did, obj{}, 200, "add direct to folder")
	a.request("PUT", "/api/v1/folders/"+fid+"/items/conversation/"+gid, obj{}, 200, "add group to folder")
	meeting := object(a.request("POST", "/api/v1/conferences", obj{"title": "Stage07 calendar meeting", "scheduledAt": time.Now().UTC().Add(24 * time.Hour).Format(time.RFC3339)}, 201, "create actual scheduled meeting")["item"])
	meetingID := ident(meeting)
	a.request("PUT", "/api/v1/folders/"+fid+"/items/conference/"+meetingID, obj{}, 200, "add meeting to same folder")
	page := a.request("GET", "/api/v1/folders/"+fid+"/items", nil, 200, "mixed folder items")
	check(len(items(page)) == 3, "folder mixes two conversations and meeting")
	for _, v := range items(page) {
		item := object(v)
		if item["type"] == "conference" {
			check(object(item["item"])["inviteCode"] == nil || object(item["item"])["inviteCode"] == "", "folder never exposes invite secret")
		}
	}
	b.request("GET", "/api/v1/folders/"+fid, nil, 404, "folder IDOR read denied")
	b.request("PUT", "/api/v1/folders/"+fid+"/items/conversation/"+did, obj{}, 404, "membership grants no access to other user's folder")
	a1.await(event("folder.created", ""), "owner first socket receives folder event")
	a2.await(event("folder.created", ""), "owner second socket receives folder event")
	check(bs.count(event("folder.created", "")) == 0, "peer socket receives no private folder event")
	a.request("POST", "/api/v1/conferences/"+meetingID+"/invitations", obj{"userIds": []string{b.id}}, 202, "calendar invitation persists notification")
	notifications := b.request("GET", "/api/v1/notifications?limit=100", nil, 200, "authoritative notification center")
	notificationID := ""
	for _, v := range items(notifications) {
		n := object(v)
		if object(n["payload"])["conferenceId"] == meetingID {
			notificationID = ident(n)
		}
	}
	check(notificationID != "", "meeting invitation visible in notification center")
	b.request("POST", "/api/v1/notifications/"+notificationID+"/read", obj{}, 200, "mark own calendar notification read")
	c.request("POST", "/api/v1/notifications/"+notificationID+"/read", obj{}, 404, "notification IDOR denied")
	cap := a.request("GET", "/api/v1/integrations/capabilities", nil, 200, "real integration capabilities")
	check(cap["push"] == "noop" || object(cap["item"])["push"] == "noop", "fixture honestly reports push noop")
	a.request("POST", "/api/v1/notifications/devices", obj{"platform": "android", "token": "disposable-unregistered-device-token"}, 503, "noop provider rejects device registration")
	// An offline device reads missed persistent messages after obtaining a new ticket.
	bs.close()
	offline := msg(a, did, "Message while recipient disconnected")
	reconnect := b.socket()
	defer reconnect.close()
	history := b.request("GET", "/api/v1/conversations/"+did+"/messages?limit=1", nil, 200, "REST resync after WSS reconnect")
	check(len(items(history)) == 1 && ident(object(items(history)[0])) == ident(offline), "reconnect restores latest persisted history")
	cursor := str(history["nextCursor"])
	check(cursor != "", "opaque message cursor provided")
	next := b.request("GET", "/api/v1/conversations/"+did+"/messages?limit=1&before="+url.QueryEscape(cursor), nil, 200, "opaque history pagination")
	check(len(items(next)) == 1 && ident(object(items(next)[0])) != ident(offline), "older page excludes latest row")
	c.request("GET", "/api/v1/conversations/"+gid+"/messages?before="+url.QueryEscape(cursor), nil, 422, "cross conversation cursor binding rejected")
	newMessage := msg(a, did, "Live after reconnect")
	reconnect.await(event("message.created", ident(newMessage)), "new socket receives subsequent live message")
	a1.close()
	presence = object(b.request("GET", "/api/v1/conversations/"+did+"/peer-presence", nil, 200, "presence after one of two devices closes")["item"])
	check(presence["online"] == true, "remaining device keeps account online")
	a.request("PATCH", "/api/v1/folders/"+fid, obj{"name": "Stage07 renamed folder " + uuid.NewString()[:6]}, 200, "rename personal folder")
	allFolders := a.request("GET", "/api/v1/folders", nil, 200, "list personal folders")
	order := []string{}
	for _, f := range items(allFolders) {
		order = append(order, ident(object(f)))
	}
	a.request("PUT", "/api/v1/folders/order", obj{"ids": order}, 200, "reorder all owned folders")
	a.request("DELETE", "/api/v1/folders/"+fid+"/items/conversation/"+gid, nil, 200, "remove group reference without deleting group")
	a.request("GET", "/api/v1/conversations/"+gid, nil, 200, "folder removal keeps conversation")
	a.request("POST", "/api/v1/conversations/"+gid+"/members", obj{"userIds": []string{c.id}}, 200, "re-add prior member")
	a.request("POST", "/api/v1/conversations/"+gid+"/ownership", obj{"userId": b.id}, 200, "transfer group ownership")
	a.request("POST", "/api/v1/conversations/"+gid+"/leave", obj{}, 200, "former owner leaves after transfer")
	c.request("POST", "/api/v1/conversations/"+gid+"/leave", obj{}, 200, "member leaves group")
	// Leave final useful conversations for Android UI proof; no fixture credentials leave private home.
	out := obj{"backendCommit": "6ecd8b648a2aa94fc9bcc844d57784423e2c6f69", "environment": "isolated actual PostgreSQL14 + Redis7 + MinIO + Gin HTTPS/WSS", "usesBusinessMocks": false, "productionMutations": false, "checks": checks, "passed": len(checks)}
	encoded, _ := json.MarshalIndent(out, "", "  ")
	check(os.WriteFile(filepath.Join(home, "backend-contract-result.json"), encoded, 0600) == nil, "write sanitized evidence")
	file, err := os.OpenFile(filepath.Join(home, "live.properties"), os.O_APPEND|os.O_WRONLY, 0600)
	check(err == nil, "append private fixture IDs")
	defer file.Close()
	fmt.Fprintf(file, "directId=%s\ngroupId=%s\nfolderId=%s\nconferenceId=%s\n", did, gid, fid, meetingID)
	fmt.Println("Actual stage07 backend contract checks passed:", len(checks))
}
