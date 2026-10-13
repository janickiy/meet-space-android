// Real backend read/access probe with explicit deterministic fixture rows.
// Seeded media/materials are not evidence of capture, STT, AI, or provider delivery.
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	content "github.com/janickiy/meet-space/internal/domain/content"
	"github.com/janickiy/meet-space/internal/domain/records"
	"github.com/janickiy/meet-space/internal/infrastructure/postgres"
	s3 "github.com/janickiy/meet-space/internal/infrastructure/storage/s3"
	pgdriver "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type obj = map[string]any

var properties map[string]string
var client *http.Client
var checks []obj
var current string
var home string

func check(ok bool, name string) {
	current = name
	if !ok {
		panic(name)
	}
	checks = append(checks, obj{"check": name, "passed": true})
}
func must(err error, name string) { check(err == nil, name) }
func object(v any) obj {
	if v == nil {
		return obj{}
	}
	return v.(map[string]any)
}
func str(v any) string {
	if v == nil {
		return ""
	}
	return v.(string)
}
func items(v obj) []any {
	if v["items"] == nil {
		return nil
	}
	return v["items"].([]any)
}
func id(v obj) string { return str(v["id"]) }

type actor struct{ token, id string }

func (a actor) request(method, path string, body any, status int, name string) obj {
	var b []byte
	if body != nil {
		b, _ = json.Marshal(body)
	}
	raw, _, _ := a.raw(method, path, b, status, name, true, nil)
	var out obj
	if len(raw) > 0 {
		must(json.Unmarshal(raw, &out), name+" JSON")
	}
	return out
}
func (a actor) raw(method, path string, b []byte, status int, name string, auth bool, headers map[string]string) ([]byte, http.Header, int) {
	current = name
	absolute := path
	if !strings.HasPrefix(path, "https://") {
		absolute = properties["origin"] + path
	}
	req, err := http.NewRequest(method, absolute, bytes.NewReader(b))
	must(err, name+" request")
	if b != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if auth && a.token != "" {
		req.Header.Set("Authorization", "Bearer "+a.token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := client.Do(req)
	must(err, name+" strict HTTPS")
	defer res.Body.Close()
	check(res.StatusCode == status, fmt.Sprintf("%s expected HTTP %d received %d", name, status, res.StatusCode))
	data, err := io.ReadAll(io.LimitReader(res.Body, 32<<20))
	must(err, name+" bounded body")
	return data, res.Header, res.StatusCode
}
func login(index int) actor {
	a := actor{}
	v := a.request("POST", "/api/v1/auth/login", obj{"email": properties[fmt.Sprint("email", index)], "password": properties[fmt.Sprint("password", index)]}, 200, "disposable account login")
	return actor{str(v["accessToken"]), id(object(v["user"]))}
}
func createMeeting(owner actor, title string) obj {
	return object(owner.request("POST", "/api/v1/conferences", obj{"title": title, "waitingRoomEnabled": false}, 201, "create fixture meeting")["item"])
}
func base(cid, rid string) string { return "/api/v1/conferences/" + cid + "/recordings/" + rid }
func file(card obj, kind string) obj {
	for _, v := range card["files"].([]any) {
		f := object(v)
		if f["fileType"] == kind {
			return f
		}
	}
	panic("expected file missing")
}
func appendProperties(values map[string]string) {
	var b strings.Builder
	for k, v := range values {
		b.WriteString(k + "=" + v + "\n")
	}
	f, err := os.OpenFile(filepath.Join(home, "live.properties"), os.O_APPEND|os.O_WRONLY, 0600)
	must(err, "open private resource properties")
	_, err = f.WriteString(b.String())
	must(err, "save private resource properties")
	f.Close()
}
func main() {
	defer func() {
		if recover() != nil {
			fmt.Println("Stage08 contract failure:", current)
			os.Exit(1)
		}
	}()
	home = os.Args[1]
	raw, err := os.ReadFile(filepath.Join(home, "live.properties"))
	must(err, "load private fixture properties")
	properties = map[string]string{}
	for _, line := range strings.Split(string(raw), "\n") {
		parts := strings.SplitN(line, "=", 2)
		if len(parts) == 2 {
			properties[parts[0]] = parts[1]
		}
	}
	ca, err := os.ReadFile(properties["caPath"])
	must(err, "load private fixture CA")
	roots := x509.NewCertPool()
	check(roots.AppendCertsFromPEM(ca), "parse fixture CA")
	client = &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}}, Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	var settings struct{ DSN, MinIO, MinIOAccess, MinIOSecret, StorageOrigin string }
	raw, err = os.ReadFile(filepath.Join(home, "settings.json"))
	must(err, "load isolated seed dependencies")
	must(json.Unmarshal(raw, &settings), "parse isolated settings")
	db, err := gorm.Open(pgdriver.Open(settings.DSN), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	must(err, "connect isolated PostgreSQL")
	sqlDB, err := db.DB()
	must(err, "obtain fixture SQL connection")
	defer sqlDB.Close()
	storage, err := s3.NewClient(context.Background(), settings.MinIO, settings.MinIOAccess, settings.MinIOSecret, "meetspace-stage08", false)
	must(err, "connect private real MinIO")
	storage.SetPublicEndpoint(settings.StorageOrigin)
	repository := postgres.NewRecordRepository(db)
	owner, member, outsider, revoked := login(0), login(1), login(2), login(3)
	conf := createMeeting(owner, "Проверка записей — тестовые данные")
	cid := id(conf)
	owner.request("POST", "/api/v1/conferences/"+cid+"/start", obj{}, 200, "start seed meeting")
	owner.request("POST", "/api/v1/conferences/"+cid+"/join", obj{}, 200, "owner joins seed meeting")
	member.request("POST", "/api/v1/conference-invites/"+str(conf["inviteCode"])+"/join", obj{}, 200, "member joins by invitation")
	revokedMembership := object(revoked.request("POST", "/api/v1/conference-invites/"+str(conf["inviteCode"])+"/join", obj{}, 200, "revocable member joins by invitation")["item"])
	other := createMeeting(owner, "Другая проверочная встреча")
	otherID := id(other)
	video, err := os.ReadFile(filepath.Join(home, "media/sample.mp4"))
	must(err, "read generated valid MP4")
	videoHash := sha256.Sum256(video)
	expectedHash := hex.EncodeToString(videoHash[:])
	check(len(video) > 1024 && bytes.Contains(video[:64], []byte("ftyp")), "generated MP4 has ISO base media container")
	makeRecord := func(mode, filename, kind, mime string) (string, obj) {
		row := object(owner.request("POST", "/api/v1/conferences/"+cid+"/recordings", obj{"mode": mode}, 202, "create actual recording state and outbox")["item"])
		rid := str(row["uuid"])
		must(repository.MarkFinalizing(context.Background(), rid), "seed declared finalizing transition")
		must(repository.MarkUploading(context.Background(), rid), "seed declared uploading transition")
		data, e := os.ReadFile(filepath.Join(home, "media", filename))
		must(e, "read real seed artifact")
		hash := sha256.Sum256(data)
		checksum := hex.EncodeToString(hash[:])
		size := int64(len(data))
		duration := 10
		uploaded, e := storage.UploadFile(context.Background(), rid+"/"+filename, filepath.Join(home, "media", filename), mime)
		must(e, "upload real artifact to private MinIO")
		final := records.RecordFile{UUID: uuid.NewString(), FileType: kind, Bucket: uploaded.Bucket, ObjectKey: uploaded.ObjectKey, FileName: filename, MimeType: mime, SizeBytes: &size, DurationSec: &duration, ChecksumSHA256: &checksum, IsPrimary: true, IsPublic: false}
		var preview *records.RecordFile
		if mode != "audio_only" {
			data, e := os.ReadFile(filepath.Join(home, "media/preview.jpg"))
			must(e, "read real preview")
			sum := sha256.Sum256(data)
			sumText := hex.EncodeToString(sum[:])
			n := int64(len(data))
			up, e := storage.UploadFile(context.Background(), rid+"/preview.jpg", filepath.Join(home, "media/preview.jpg"), "image/jpeg")
			must(e, "upload real preview")
			preview = &records.RecordFile{UUID: uuid.NewString(), FileType: records.FileTypePreviewJPG, Bucket: up.Bucket, ObjectKey: up.ObjectKey, FileName: "preview.jpg", MimeType: "image/jpeg", SizeBytes: &n, ChecksumSHA256: &sumText}
		}
		must(repository.SaveFinalArtifacts(context.Background(), rid, final, preview, nil), "commit ready artifacts through actual repository")
		return rid, object(owner.request("GET", base(cid, rid), nil, 200, "read seeded ready card from actual API")["item"])
	}
	rid, card := makeRecord("composite", "sample.mp4", records.FileTypeFinalMP4, "video/mp4")
	rid2, _ := makeRecord("screen_focus", "sample.mp4", records.FileTypeFinalMP4, "video/mp4")
	audioID, audioCard := makeRecord("audio_only", "sample.m4a", records.FileTypeFinalAudio, "audio/mp4")
	// Add an explicit technical artifact to exercise client allowlisting. API really
	// exposes its metadata; product code must select final media/preview only.
	recordRow, err := repository.FindByUUID(context.Background(), rid)
	must(err, "read scoped seed record row")
	up, err := storage.UploadFile(context.Background(), rid+"/debug.log", filepath.Join(home, "media/debug.log"), "text/plain")
	must(err, "store excluded technical fixture artifact")
	debugFile := records.RecordFile{UUID: uuid.NewString(), RecordID: recordRow.ID, FileType: records.FileTypeDebugLog, Bucket: up.Bucket, ObjectKey: up.ObjectKey, FileName: "debug.log", MimeType: "text/plain"}
	must(db.Create(&debugFile).Error, "seed explicit debug metadata for client allowlist")
	transcriptID := uuid.NewString()
	now := time.Now().UTC()
	transcript := content.Transcript{ID: transcriptID, ConferenceID: cid, RecordingID: rid, Status: content.Ready, Language: "ru", Provider: "fixture_seed", Generation: 1, CreatedAt: now, UpdatedAt: now, ProcessedAt: &now}
	must(db.Table("transcripts").Create(&transcript).Error, "seed explicitly labeled historical transcript metadata")
	segmentIDs := []string{}
	for i := 0; i < 205; i++ {
		segment := content.Segment{ID: uuid.NewString(), TranscriptID: transcriptID, Ordinal: i, StartMS: int64(i * 40), EndMS: int64((i + 1) * 40), Text: fmt.Sprintf("Проверочный материал %03d. Искусственные данные стенда, не результат распознавания речи.", i+1)}
		must(db.Table("transcript_segments").Create(&segment).Error, "seed labeled transcript segment")
		segmentIDs = append(segmentIDs, segment.ID)
	}
	summaryID := uuid.NewString()
	structured, _ := json.Marshal(content.SummaryOutput{Summary: "Проверочная сводка. Эти данные созданы для теста чтения, не внешней AI-системой.", KeyPoints: []string{"Видеофайл существует в приватном хранилище", "Материалы защищены правами встречи"}, ActionItems: []content.ActionItem{{Text: "Проверить доступ участника", SourceSegmentIDs: []string{segmentIDs[0]}}}, Topics: []string{"Проверка клиента"}})
	must(db.Exec(`INSERT INTO meeting_summaries(id,conference_id,transcript_id,transcript_generation,status,structured_output,provider,model,prompt_version,schema_version,generation,search_text,processed_at) VALUES (?,?,?,1,'ready',?::jsonb,'fixture_seed','fixture_seed','fixture-v1','meeting-summary-v1',1,'Проверочная сводка',?)`, summaryID, cid, transcriptID, string(structured), now).Error, "seed labeled historical summary")
	// Real handlers, repository authorization, cache headers and pagination.
	actor{}.request("GET", base(cid, rid), nil, 401, "anonymous recording read denied")
	outsider.request("GET", "/api/v1/conferences/"+cid+"/recordings", nil, 403, "outsider recording list denied")
	outsider.request("GET", base(cid, rid), nil, 403, "outsider recording detail denied")
	owner.request("GET", base(otherID, rid), nil, 404, "cross conference recording ID rejected")
	pages := map[string]bool{}
	for offset := 0; offset < 3; offset++ {
		page := member.request("GET", "/api/v1/conferences/"+cid+"/recordings?limit=1&offset="+strconv.Itoa(offset), nil, 200, "actual recording offset pagination")
		check(len(items(page)) == 1, "one recording per bounded page")
		r := str(object(items(page)[0])["uuid"])
		check(!pages[r], "recording pages do not overlap")
		pages[r] = true
	}
	check(len(pages) == 3, "all three ready modes traversed")
	owner.request("GET", "/api/v1/conferences/"+cid+"/recordings?limit=101", nil, 422, "recording page bound enforced")
	for _, suffix := range []string{"/transcript", "/transcript/segments", "/summary"} {
		outsider.request("GET", base(cid, rid)+suffix, nil, 403, "outsider private material denied")
	}
	meta := member.request("GET", base(cid, rid)+"/transcript", nil, 200, "actual transcript metadata")
	check(meta["enabled"] == false && meta["providerMode"] == "noop" && meta["canRetry"] == false, "no STT provider success simulated")
	check(id(object(meta["item"])) == transcriptID && object(meta["item"])["status"] == "ready", "historical seeded material remains readable when provider disabled")
	seen := map[string]bool{}
	for offset := 0; offset < 205; offset += 100 {
		page := member.request("GET", base(cid, rid)+"/transcript/segments?limit=100&offset="+strconv.Itoa(offset), nil, 200, "actual transcript pagination")
		check(page["total"] == float64(205), "actual segment total")
		for _, v := range items(page) {
			sid := id(object(v))
			check(!seen[sid], "segments do not repeat across pages")
			seen[sid] = true
		}
	}
	check(len(seen) == 205, "all seeded segments traversed")
	member.request("GET", base(cid, rid)+"/transcript/segments?limit=201", nil, 422, "segment upper limit enforced")
	member.request("GET", base(cid, rid)+"/transcript/segments?offset=-1", nil, 422, "negative segment offset rejected")
	raw, headers, _ := member.raw("GET", base(cid, rid)+"/summary", nil, 200, "read actual summary", true, nil)
	var summary obj
	must(json.Unmarshal(raw, &summary), "summary JSON")
	check(strings.Contains(headers.Get("Cache-Control"), "no-store") && headers.Get("X-Content-Type-Options") == "nosniff", "private materials disable cache and sniffing")
	check(summary["enabled"] == false && summary["canRegenerate"] == false && id(object(summary["item"])) == summaryID, "actual stored summary with disabled AI capabilities")
	empty := owner.request("GET", base(cid, rid2)+"/transcript", nil, 200, "nullable missing transcript metadata")
	check(empty["item"] == nil, "missing content remains null")
	owner.request("GET", base(cid, rid2)+"/transcript/segments", nil, 404, "absent transcript segments are not fake empty success")
	owner.request("POST", base(cid, rid)+"/transcript/retry", obj{}, 503, "disabled transcription retry truthful unavailable")
	owner.request("POST", base(cid, rid)+"/summary/regenerate", obj{}, 503, "disabled AI regenerate truthful unavailable")
	primary := file(card, records.FileTypeFinalMP4)
	mediaURL := str(primary["url"])
	parsed, err := url.Parse(mediaURL)
	must(err, "parse real signed media URL")
	check(parsed.Scheme == "https" && parsed.Query().Get("X-Amz-Expires") == "86400", "API uses signed HTTPS URL with actual 24 hour TTL")
	download, _, _ := member.raw("GET", mediaURL, nil, 200, "signed object download without API JWT", false, nil)
	got := sha256.Sum256(download)
	check(hex.EncodeToString(got[:]) == expectedHash && bytes.Equal(download, video), "download SHA256 and bytes match generated video")
	ranged, h, _ := member.raw("GET", mediaURL, nil, 206, "real MinIO range request", false, map[string]string{"Range": "bytes=8-39"})
	check(bytes.Equal(ranged, video[8:40]) && strings.HasPrefix(h.Get("Content-Range"), "bytes 8-39/"), "range payload and Content-Range match source")
	unsigned := *parsed
	unsigned.RawQuery = ""
	actor{}.raw("GET", unsigned.String(), nil, 403, "private object denies unsigned access", false, nil)
	bad := *parsed
	q := bad.Query()
	q.Set("X-Amz-Signature", strings.Repeat("0", 64))
	bad.RawQuery = q.Encode()
	actor{}.raw("GET", bad.String(), nil, 403, "invalid signature rejected", false, nil)
	short, err := storage.PresignedGetURL(context.Background(), str(primary["objectKey"]), time.Second)
	must(err, "create genuinely short lived storage URL")
	time.Sleep(2200 * time.Millisecond)
	actor{}.raw("GET", short, nil, 403, "actual MinIO signed URL expiration", false, nil)
	owner.request("POST", "/api/v1/conferences/"+cid+"/participants/"+id(revokedMembership)+"/moderation", obj{"action": "kick"}, 200, "real backend membership revocation")
	for _, suffix := range []string{"", "/transcript", "/transcript/segments", "/summary"} {
		revoked.request("GET", base(cid, rid)+suffix, nil, 403, "revoked member denied fresh recording materials")
	}
	// Record the server limitation: pre-issued S3 URLs are bearer credentials.
	revoked.raw("GET", mediaURL, nil, 200, "known limitation existing signed URL survives membership revoke until expiry", false, nil)
	for _, path := range []string{"/api/v1/records", "/api/records", "/debug/records", "/debug/webrtc-smoke"} {
		actor{}.request("GET", path, nil, 410, "retired debug or legacy recording route stays closed")
	}
	owner.request("POST", "/api/v1/conferences/"+cid+"/finish", obj{}, 200, "finish fixture meeting for history navigation")
	history := object(member.request("GET", "/api/v1/conferences/"+cid+"/history", nil, 200, "admitted former participant can read real finished history")["item"])
	check(history["participantCount"].(float64) >= 2, "history includes real authorized memberships")
	member.request("GET", base(cid, rid), nil, 200, "admitted left member retains recording history")
	owner.request("POST", "/api/v1/conferences/"+otherID+"/cancel", obj{}, 200, "cancel second fixture meeting for cursor traversal")
	timeline := owner.request("GET", "/api/v1/me/conferences?view=past&scope=owned&limit=1", nil, 200, "real meeting cursor page for recording picker")
	if cursor := str(timeline["nextCursor"]); cursor != "" {
		next := owner.request("GET", "/api/v1/me/conferences?view=past&scope=owned&limit=1&cursor="+url.QueryEscape(cursor), nil, 200, "opaque meeting cursor advances")
		check(id(object(items(timeline)[0])) != id(object(items(next)[0])), "meeting cursor pages differ")
	}
	appendProperties(map[string]string{"conferenceId": cid, "otherConferenceId": otherID, "recordingId": rid, "secondRecordingId": rid2, "audioRecordingId": audioID, "videoFileId": str(primary["uuid"]), "previewFileId": str(file(card, records.FileTypePreviewJPG)["uuid"]), "audioFileId": str(file(audioCard, records.FileTypeFinalAudio)["uuid"]), "debugFileId": debugFile.UUID, "transcriptId": transcriptID, "summaryId": summaryID, "segmentCount": "205", "mediaSha256": expectedHash, "mediaBytes": strconv.Itoa(len(video)), "ownerUserId": owner.id, "memberUserId": member.id, "outsiderUserId": outsider.id, "revokedUserId": revoked.id, "seedComplete": "true"})
	result := obj{"backendCommit": "6ecd8b648a2aa94fc9bcc844d57784423e2c6f69", "environment": "isolated actual Gin/PostgreSQL/Redis/MinIO over validated HTTPS", "checks": checks, "passed": len(checks), "usesBusinessMocks": false, "seededArtifactsAndMaterials": true, "capturePipelineExercised": false, "externalSttOrAiExercised": false, "productionMutations": false, "signedUrlLifetimeSeconds": 86400, "knownLimitation": "Already-issued storage bearer URLs survive membership revocation until expiration"}
	raw, _ = json.MarshalIndent(result, "", "  ")
	must(os.WriteFile(filepath.Join(home, "backend-contract-result.json"), append(raw, '\n'), 0600), "write sanitized contract evidence")
	fmt.Println("Stage08 actual backend checks passed:", result["passed"])
}
