// Disposable Android beta010 fixture. All business routes use the audited backend.
package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/janickiy/meet-space/internal/config"
	"github.com/janickiy/meet-space/internal/domain/media"
	"github.com/janickiy/meet-space/internal/domain/realtime"
	redisinfra "github.com/janickiy/meet-space/internal/infrastructure/redis"
	"github.com/janickiy/meet-space/internal/infrastructure/sfu"
	"github.com/janickiy/meet-space/internal/transport/mediaworker"
	ws "github.com/janickiy/meet-space/internal/transport/websocket"
	mediausecase "github.com/janickiy/meet-space/internal/usecase/media"
	realtimeusecase "github.com/janickiy/meet-space/internal/usecase/realtime"
	"github.com/pion/logging"
	"github.com/pion/turn/v5"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/gin-gonic/gin"
	authapp "github.com/janickiy/meet-space/internal/app/auth"
	callsapp "github.com/janickiy/meet-space/internal/app/calls"
	chatapp "github.com/janickiy/meet-space/internal/app/chat"
	conferencesapp "github.com/janickiy/meet-space/internal/app/conferences"
	personalapp "github.com/janickiy/meet-space/internal/app/personal"
	platformapp "github.com/janickiy/meet-space/internal/app/platform"
	"github.com/janickiy/meet-space/internal/buildinfo"
	"github.com/janickiy/meet-space/internal/infrastructure/postgres"
	"github.com/janickiy/meet-space/internal/infrastructure/security"
	s3 "github.com/janickiy/meet-space/internal/infrastructure/storage/s3"
	httptransport "github.com/janickiy/meet-space/internal/transport/http"
	middleware "github.com/janickiy/meet-space/internal/transport/http/middleware"
	authusecase "github.com/janickiy/meet-space/internal/usecase/auth"
	callsusecase "github.com/janickiy/meet-space/internal/usecase/calls"
	chatusecase "github.com/janickiy/meet-space/internal/usecase/chat"
	conferencesusecase "github.com/janickiy/meet-space/internal/usecase/conferences"
	personalusecase "github.com/janickiy/meet-space/internal/usecase/personal"
	platformusecase "github.com/janickiy/meet-space/internal/usecase/platform"
	pgdriver "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type settings struct{ DSN, JWTSecret, Certificate, PrivateKey, Listen, Redis, LAN, TURNSecret, BackendCommit, MinIO, MinIOAccess, MinIOSecret string }

func main() {
	if len(os.Args) != 2 {
		panic("fixture settings path required")
	}
	raw, err := os.ReadFile(os.Args[1])
	must(err)
	var fixture settings
	must(json.Unmarshal(raw, &fixture))
	if fixture.Listen == "" {
		fixture.Listen = "127.0.0.1:18410"
	}
	db, err := gorm.Open(pgdriver.Open(fixture.DSN), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	must(err)
	must(postgres.RunMigrations(db, "database/migrations"))
	tokens, err := security.NewTokenService(fixture.JWTSecret)
	must(err)
	users := postgres.NewUserRepository(db)
	auth, err := authusecase.NewService(users, security.PasswordHasher{}, tokens)
	must(err)
	auth.WithSessions(postgres.NewAuthSessionRepository(db))
	authHandler := authapp.NewHandler(auth).WithSessionCookies("https://"+fixture.Listen, true)
	conferences := conferencesusecase.NewService(postgres.NewConferenceRepository(db), users, func() (string, error) {
		var code [24]byte
		_, err := rand.Read(code[:])
		return base64.RawURLEncoding.EncodeToString(code[:]), err
	})
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	must(router.SetTrustedProxies(nil))
	authentication := middleware.Authenticate(auth)
	httptransport.RegisterPlatformRoutes(router, authHandler, conferencesapp.NewHandler(conferences), authentication)
	capabilities := &platformapp.Handler{Service: &platformusecase.Service{}, BuildVersion: "beta010"}
	router.GET("/api/v1/capabilities", authentication, capabilities.Capabilities)

	ctx := context.Background()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	os.Setenv("APP_ENV", "local")
	os.Setenv("JWT_SECRET", fixture.JWTSecret)
	os.Setenv("MEDIA_WORKER_INTERNAL_URL", "http://127.0.0.1:18910")
	os.Setenv("MEDIA_HTTP_PORT", "18910")
	os.Setenv("MEDIA_UDP_PORT", "50110")
	os.Setenv("MEDIA_TCP_PORT", "0")
	os.Setenv("MEDIA_MAX_PEERS", "5")
	os.Setenv("MEDIA_NAT_IPS", fixture.LAN)
	os.Setenv("WS_ALLOWED_ORIGINS", "https://10.0.2.2:18410,https://127.0.0.1:18410")
	os.Setenv("TURN_URLS", "turn:"+fixture.LAN+":13410?transport=udp")
	os.Setenv("TURN_SHARED_SECRET", fixture.TURNSecret)
	os.Setenv("TURN_FORCE_RELAY", "true")
	os.Setenv("WS_MESSAGES_PER_SECOND", "100")
	os.Setenv("WS_MESSAGE_BURST", "200")
	cfg, err := config.LoadMedia()
	must(err)
	rt, err := config.LoadRealtime()
	must(err)
	redis, err := redisinfra.NewClient(ctx, fixture.Redis, "", 0)
	must(err)
	defer redis.Close()
	limiter := redisinfra.NewRateLimiter(redis)
	bus := redisinfra.NewNotificationBus(redis, "android-beta010")
	personalRepo := postgres.NewPersonalRepository(db)
	presence := redisinfra.NewUserPresence(redis, "android-beta010", rt.SessionTTL)
	globalWS := ws.NewUserHandler(auth, redisinfra.NewRealtimeStore(redis, "android-beta010:user-ws"), limiter, rt, bus, presence, personalRepo)
	defer globalWS.Shutdown()
	globalWS.RegisterRoutes(router)
	events := &personalusecase.Events{Members: personalRepo, Bus: bus}
	storage, err := s3.NewClient(ctx, fixture.MinIO, fixture.MinIOAccess, fixture.MinIOSecret, "meetspace-beta010", false)
	must(err)
	chat, err := chatusecase.NewService(ctx, postgres.NewDirectChatRepository(db), storage, events)
	must(err)
	personalHandler := &personalapp.Handler{Repo: personalRepo, Events: events, Presence: presence}
	httptransport.RegisterPersonalRoutes(router, personalHandler, chatapp.NewHandler(chat).ForConversations(), authentication, limiter)
	store := redisinfra.NewRealtimeStore(redis, "android-beta010:realtime")
	registry := redisinfra.NewMediaRegistry(redis, "android-beta010:media")
	hub, err := realtimeusecase.NewHub(postgres.NewSessionRepository(db), store, rt.SessionTTL, logger)
	must(err)
	defer hub.Shutdown()
	callService := &callsusecase.Service{Repo: postgres.NewCallRepository(db), Bus: bus, Observer: hub}
	httptransport.RegisterCallRoutes(router, &callsapp.Handler{Service: callService}, authentication, personalHandler.AccountOnly, limiter)
	go callService.Run(ctx)
	go chat.Run(ctx)

	tickets, err := security.NewMediaTickets(cfg.TicketSecret, cfg.TicketTTL)
	must(err)
	engine, err := sfu.NewManager(sfu.Options{WorkerID: cfg.WorkerID, UDPPort: cfg.UDPPort, NATIPs: cfg.NATIPs,
		MaxPeers: cfg.MaxPeers, MaxRooms: cfg.MaxRooms, MaxPublishedTracks: 4, MaxAudioTracks: 2, MaxVideoTracks: 2, MaxScreenSharers: 1,
		QueueSize: 128, EgressQueueSize: 128, ICEDisconnectedTimeout: cfg.ICEDisconnectedTimeout, ICEFailedTimeout: cfg.ICEFailedTimeout,
		ICEKeepaliveInterval: cfg.ICEKeepaliveInterval, NegotiationTimeout: cfg.NegotiationTimeout, Logger: logger,
		Emit: func(binding media.Binding, kind string, data any) {
			event := realtime.Event(kind, binding.ConferenceID, data)
			_ = store.Publish(ctx, realtime.Bus{Kind: "event", ConferenceID: binding.ConferenceID, ConnectionID: binding.ConnectionID, Event: &event})
		},
	})
	must(err)
	worker := mediaworker.NewHandler(cfg, registry, store, tickets, engine, func() any { return engine.Snapshot() }, logger)
	_, err = worker.Start(ctx)
	must(err)
	defer worker.Stop(ctx)
	listener, err := net.Listen("tcp", "127.0.0.1:18910")
	must(err)
	go http.Serve(listener, worker.Routes())
	go func() {
		for {
			raw, _ := json.Marshal(engine.Snapshot())
			os.WriteFile(filepath.Join(filepath.Dir(os.Args[1]), "snapshot.json"), raw, 0600)
			// Test-assembly diagnostics only. No endpoint or production handler is added.
			dir := filepath.Dir(os.Args[1])
			if _, err := os.Stat(filepath.Join(dir, "quality-gc-request")); err == nil {
				os.Remove(filepath.Join(dir, "quality-gc-request"))
				runtime.GC()
			}
			var memory runtime.MemStats
			runtime.ReadMemStats(&memory)
			metrics, _ := json.Marshal(map[string]any{
				"unixMillis": time.Now().UnixMilli(), "goroutines": runtime.NumGoroutine(),
				"heapAllocBytes": memory.HeapAlloc, "heapObjects": memory.HeapObjects,
				"totalAllocBytes": memory.TotalAlloc, "gcCount": memory.NumGC,
			})
			target := filepath.Join(dir, "runtime-snapshot.json")
			if os.WriteFile(target+".tmp", metrics, 0600) == nil {
				os.Rename(target+".tmp", target)
			}
			time.Sleep(time.Second)
		}
	}()
	client := mediausecase.NewHTTPClient(cfg.InternalSecret, cfg.OperationTimeout)
	defer client.Close()
	controller := mediausecase.NewController(registry, tickets, client, hub, store, cfg, rt)
	controller.SetPolicyProvider(postgres.NewConferenceRepository(db))
	controls := conferencesusecase.NewControlService(postgres.NewConferenceRepository(db), controller, hub)
	hub.SetDisconnectObserver(realtimeusecase.DisconnectObservers{controls, controller})
	conferences.SetObserver(hub)
	httptransport.RegisterControlRoutes(router, conferencesapp.NewControlHandler(controls), authentication)
	ws.NewHandler(hub, auth, store, nil, rt).SetMedia(controller).RegisterRoutes(router)
	go controls.Run(ctx)
	turnConn, err := net.ListenPacket("udp4", "0.0.0.0:13410")
	must(err)
	turnLogger := logging.NewDefaultLeveledLoggerForScope("fixture-turn", logging.LogLevelDisabled, io.Discard)
	turnServer, err := turn.NewServer(turn.ServerConfig{Realm: "meetspace-beta010", AuthHandler: turn.LongTermTURNRESTAuthHandler(fixture.TURNSecret, turnLogger),
		PacketConnConfigs: []turn.PacketConnConfig{{PacketConn: turnConn, RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{RelayAddress: net.ParseIP(fixture.LAN), Address: "0.0.0.0"}}},
	})
	must(err)
	defer turnServer.Close()
	buildinfo.Version = "beta010"
	buildinfo.Commit = fixture.BackendCommit
	buildinfo.BuildTime = "unknown"
	router.GET("/version", gin.WrapF(buildinfo.Handler))
	server := &http.Server{Addr: fixture.Listen, Handler: router, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 32 * 1024}
	fmt.Println("Disposable beta010 HTTPS fixture ready")
	must(server.ListenAndServeTLS(fixture.Certificate, fixture.PrivateKey))
}

func must(err error) {
	if err != nil {
		panic("beta010 fixture setup failed")
	}
}
