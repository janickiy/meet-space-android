// Real Pion endpoints for Android interoperability. The first peer echoes Android RTP;
// other peers receive it. No signaling or media success is mocked.
package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type env struct {
	home, origin string
	props        map[string]string
	client       *http.Client
	tls          *tls.Config
}
type timeline struct {
	seq             uint16
	out, last, ssrc uint32
}

func (t *timeline) rewrite(p *rtp.Packet, step uint32) {
	delta := p.Timestamp - t.last
	if p.SSRC != t.ssrc || delta > 900000 {
		delta = step
	}
	t.last = p.Timestamp
	t.ssrc = p.SSRC
	t.out += delta
	t.seq++
	p.SequenceNumber = t.seq
	p.Timestamp = t.out
}

type peer struct {
	forwardMu                         sync.Mutex
	videoTimeline, audioTimeline      timeline
	videoSSRC                         atomic.Uint32
	conn                              *websocket.Conn
	pc                                *webrtc.PeerConnection
	mu                                sync.Mutex
	mediaID, conf, negotiation, ready string
	pending, dirty                    bool
	remoteICE                         []webrtc.ICECandidateInit
	audio, video                      *webrtc.TrackLocalStaticRTP
	packets                           atomic.Int64
	screens                           atomic.Int64
	tracksMu                          sync.RWMutex
	sources                           map[string]string
	target                            string
	echo                              bool
	echoScreen                        bool
	env                               *env
	token                             string
}

func must(err error) {
	if err != nil {
		panic("stage06 peer operation failed")
	}
}
func (e *env) api(token, path, method string, data any) map[string]any {
	raw, _ := json.Marshal(data)
	req, err := http.NewRequest(method, e.origin+"/api/v1/"+path, bytes.NewReader(raw))
	must(err)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := e.client.Do(req)
	must(err)
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		panic(fmt.Sprintf("stage06 HTTP %d", res.StatusCode))
	}
	var out map[string]any
	must(json.NewDecoder(res.Body).Decode(&out))
	return out
}
func str(m map[string]any, key string) string { v, _ := m[key].(string); return v }
func item(m map[string]any) map[string]any    { return m["item"].(map[string]any) }
func (p *peer) send(kind string, data any) string {
	p.mu.Lock()
	defer p.mu.Unlock()
	id := uuid.NewString()
	must(p.conn.WriteJSON(map[string]any{"version": 1, "id": id, "conferenceId": p.conf, "type": kind, "timestamp": time.Now().UTC(), "data": data}))
	return id
}
func (p *peer) offer() {
	if p.pending {
		p.dirty = true
		return
	}
	p.pending = true
	p.dirty = false
	p.negotiation = uuid.NewString()
	sdp, err := p.pc.CreateOffer(nil)
	must(err)
	must(p.pc.SetLocalDescription(sdp))
	publications := []map[string]any{}
	if p.echo {
		for _, t := range p.pc.GetTransceivers() {
			if t.Sender() == nil || t.Sender().Track() == nil {
				continue
			}
			source := "camera"
			if p.echoScreen {
				source = "video/screen"
			}
			if t.Kind() == webrtc.RTPCodecTypeAudio {
				source = "microphone"
			}
			publications = append(publications, map[string]any{"mid": t.Mid(), "source": source, "trackId": t.Sender().Track().ID()})
		}
	}
	p.send("media.offer", map[string]any{"mediaPeerId": p.mediaID, "negotiationId": p.negotiation, "sdp": p.pc.LocalDescription().SDP, "publications": publications})
}
func (p *peer) run(ice webrtc.Configuration) {
	var err error
	engine := &webrtc.MediaEngine{}
	must(engine.RegisterCodec(webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2}, PayloadType: 111}, webrtc.RTPCodecTypeAudio))
	must(engine.RegisterCodec(webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000, RTCPFeedback: []webrtc.RTCPFeedback{{Type: "nack"}, {Type: "nack", Parameter: "pli"}}}, PayloadType: 96}, webrtc.RTPCodecTypeVideo))
	interceptors := &interceptor.Registry{}
	must(webrtc.RegisterDefaultInterceptors(engine, interceptors))
	api := webrtc.NewAPI(webrtc.WithMediaEngine(engine), webrtc.WithInterceptorRegistry(interceptors))
	p.pc, err = api.NewPeerConnection(ice)
	must(err)
	p.pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		var data any
		if c != nil {
			data = c.ToJSON()
			fmt.Println("local ICE candidate type", c.Typ.String())
		}
		p.send("media.ice", map[string]any{"mediaPeerId": p.mediaID, "candidate": data})
	})
	p.pc.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		for {
			packet, _, err := track.ReadRTP()
			if err != nil {
				return
			}
			p.packets.Add(1)
			p.tracksMu.RLock()
			source := p.sources[track.StreamID()+":"+track.Kind().String()]
			p.tracksMu.RUnlock()
			if strings.HasSuffix(track.StreamID(), "-screen") {
				p.screens.Add(1)
			}
			if p.echo && source != "" {
				p.forwardMu.Lock()
				if source == "camera" {
					p.videoSSRC.Store(uint32(track.SSRC()))
					p.videoTimeline.rewrite(packet, 3000)
					_ = p.video.WriteRTP(packet)
				}
				if source == "microphone" {
					p.audioTimeline.rewrite(packet, 960)
					_ = p.audio.WriteRTP(packet)
				}
				p.forwardMu.Unlock()
			}
		}
	})
	if p.echo {
		p.audio, err = webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2}, "echo-audio", "echo")
		must(err)
		p.video, err = webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "echo-camera", "echo")
		must(err)
		for _, track := range []webrtc.TrackLocal{p.audio, p.video} {
			t, err := p.pc.AddTransceiverFromTrack(track, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendrecv})
			must(err)
			go func() {
				for {
					packets, _, err := t.Sender().ReadRTCP()
					if err != nil {
						return
					}
					for _, packet := range packets {
						if _, ok := packet.(*rtcp.PictureLossIndication); ok && p.videoSSRC.Load() != 0 {
							_ = p.pc.WriteRTCP([]rtcp.Packet{&rtcp.PictureLossIndication{MediaSSRC: p.videoSSRC.Load()}})
						}
					}
				}
			}()
		}
	} else {
		for _, kind := range []webrtc.RTPCodecType{webrtc.RTPCodecTypeAudio, webrtc.RTPCodecTypeVideo} {
			_, err = p.pc.AddTransceiverFromKind(kind, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly})
			must(err)
		}
	}
	for i := 0; i < 9; i++ {
		for _, kind := range []webrtc.RTPCodecType{webrtc.RTPCodecTypeAudio, webrtc.RTPCodecTypeVideo} {
			_, err = p.pc.AddTransceiverFromKind(kind, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly})
			must(err)
		}
	}
	p.offer()
}
func (p *peer) catalog(data map[string]any) {
	p.tracksMu.Lock()
	defer p.tracksMu.Unlock()
	p.sources = map[string]string{}
	for _, entry := range data["tracks"].([]any) {
		t := entry.(map[string]any)
		if str(t, "participantId") == p.target {
			p.sources[str(t, "streamId")+":"+str(t, "kind")] = str(t, "source")
		}
	}
}
func main() {
	if len(os.Args) != 2 {
		panic("fixture home required")
	}
	e := &env{home: os.Args[1], props: map[string]string{}}
	raw, err := os.ReadFile(filepath.Join(e.home, "live.properties"))
	must(err)
	for _, line := range strings.Split(string(raw), "\n") {
		v := strings.SplitN(line, "=", 2)
		if len(v) == 2 {
			e.props[v[0]] = v[1]
		}
	}
	e.origin = e.props["origin"]
	ca, err := os.ReadFile(e.props["caPath"])
	must(err)
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(ca) {
		panic("CA invalid")
	}
	e.tls = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	e.client = &http.Client{Transport: &http.Transport{TLSClientConfig: e.tls}, Timeout: 15 * time.Second}
	ownerToken := str(e.api("", "auth/login", "POST", map[string]string{"email": e.props["email"], "password": e.props["password"]}), "accessToken")
	conference := item(e.api(ownerToken, "conferences", "POST", map[string]any{"title": "Android · Pion interoperability"}))
	conf := str(conference, "id")
	invite := str(conference, "inviteCode")
	e.api(ownerToken, "conferences/"+conf+"/start", "POST", map[string]any{})
	member := item(e.api(ownerToken, "conferences/"+conf+"/join", "POST", map[string]any{}))
	target := str(member, "id")
	appendFile, err := os.OpenFile(filepath.Join(e.home, "live.properties"), os.O_APPEND|os.O_WRONLY, 0600)
	must(err)
	_, err = appendFile.WriteString("conferenceId=" + conf + "\n")
	must(err)
	appendFile.Close()
	peers := []*peer{}
	count := 4
	if raw := os.Getenv("MEETSPACE_STAGE06_PEERS"); raw != "" {
		count, err = strconv.Atoi(raw)
		must(err)
		if count < 1 || count > 4 {
			panic("peer count out of range")
		}
	}
	for i := 1; i <= count; i++ {
		token := str(e.api("", "auth/login", "POST", map[string]string{"email": e.props[fmt.Sprintf("email%d", i)], "password": e.props[fmt.Sprintf("password%d", i)]}), "accessToken")
		e.api(token, "conferences/"+conf+"/join", "POST", map[string]any{"inviteCode": invite})
		iceRaw := e.api(token, "webrtc/config", "GET", nil)
		buf, _ := json.Marshal(iceRaw["iceServers"])
		ice := webrtc.Configuration{ICETransportPolicy: webrtc.ICETransportPolicyRelay, BundlePolicy: webrtc.BundlePolicyMaxBundle}
		must(json.Unmarshal(buf, &ice.ICEServers))
		ticket := str(e.api(token, "conferences/"+conf+"/ws-ticket", "POST", map[string]any{}), "ticket")
		dial := websocket.Dialer{TLSClientConfig: e.tls, Subprotocols: []string{"go-recorder.v1"}}
		conn, _, err := dial.Dial(strings.Replace(e.origin, "https:", "wss:", 1)+"/api/v1/conferences/"+conf+"/ws?ticket="+ticket, nil)
		must(err)
		p := &peer{conn: conn, conf: conf, target: target, echo: i == 1, echoScreen: os.Getenv("MEETSPACE_STAGE06_ECHO_SCREEN") == "1", sources: map[string]string{}, env: e, token: token}
		peers = append(peers, p)
		go func() {
			for {
				var event map[string]any
				if err := conn.ReadJSON(&event); err != nil {
					return
				}
				data, ok := event["data"].(map[string]any)
				if !ok {
					continue
				}
				switch str(event, "type") {
				case "conference.state":
					if p.mediaID == "" {
						p.send("media.join", map[string]any{})
					}
				case "media.joined":
					if p.pc != nil {
						continue
					}
					p.mediaID = str(data, "mediaPeerId")
					p.catalog(data)
					p.run(ice)
				case "media.answer":
					for _, line := range strings.Split(str(data, "sdp"), "\n") {
						if strings.HasPrefix(line, "a=candidate:") {
							fields := strings.Fields(line)
							if len(fields) > 7 {
								fmt.Println("remote candidate", fields[4], fields[5], fields[7])
							}
						}
					}
					must(p.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: str(data, "sdp")}))
					for _, c := range p.remoteICE {
						if p.pc.RemoteDescription() == nil {
							p.remoteICE = append(p.remoteICE, c)
						} else {
							must(p.pc.AddICECandidate(c))
						}
					}
					p.remoteICE = nil
					p.ready = p.send("media.ready", map[string]any{"mediaPeerId": p.mediaID, "negotiationId": p.negotiation})
				case "ack":
					if str(event, "replyTo") == p.ready {
						p.pending = false
						if p.dirty {
							p.offer()
						}
					}
				case "media.renegotiate":
					p.offer()
				case "media.ice":
					if data["candidate"] != nil {
						raw, _ := json.Marshal(data["candidate"])
						var c webrtc.ICECandidateInit
						must(json.Unmarshal(raw, &c))
						if p.pc.RemoteDescription() == nil {
							p.remoteICE = append(p.remoteICE, c)
						} else {
							must(p.pc.AddICECandidate(c))
						}
					}
				case "media.tracks":
					p.catalog(data)
				case "error":
					_ = os.WriteFile(filepath.Join(e.home, "peer-error.txt"), []byte("Signaling error: "+str(data, "code")), 0600)
				}
			}
		}()
		time.Sleep(500 * time.Millisecond)
	}
	fmt.Println("Real Pion peers started; private conference ID saved")
	for {
		time.Sleep(time.Second)
		list := []map[string]any{}
		for _, p := range peers {
			status := "new"
			if p.pc != nil {
				status = p.pc.ConnectionState().String()
			}
			list = append(list, map[string]any{"state": status, "rtpPackets": p.packets.Load(), "screenPackets": p.screens.Load()})
		}
		raw, _ := json.Marshal(list)
		_ = os.WriteFile(filepath.Join(e.home, "peers-stats.json"), raw, 0600)
	}
}

var _ io.Reader
