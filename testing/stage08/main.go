// Disposable stage08 assembly of the unmodified pinned backend business services.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"github.com/gin-gonic/gin"
	authapp "github.com/janickiy/meet-space/internal/app/auth"
	chatapp "github.com/janickiy/meet-space/internal/app/chat"
	conferencesapp "github.com/janickiy/meet-space/internal/app/conferences"
	contentapp "github.com/janickiy/meet-space/internal/app/content"
	foldersapp "github.com/janickiy/meet-space/internal/app/folders"
	integrationsapp "github.com/janickiy/meet-space/internal/app/integrations"
	notificationsapp "github.com/janickiy/meet-space/internal/app/notifications"
	personalapp "github.com/janickiy/meet-space/internal/app/personal"
	platformapp "github.com/janickiy/meet-space/internal/app/platform"
	recordingsapp "github.com/janickiy/meet-space/internal/app/recordings"
	"github.com/janickiy/meet-space/internal/buildinfo"
	"github.com/janickiy/meet-space/internal/config"
	"github.com/janickiy/meet-space/internal/infrastructure/contentproviders"
	"github.com/janickiy/meet-space/internal/infrastructure/postgres"
	"github.com/janickiy/meet-space/internal/infrastructure/providers"
	redisinfra "github.com/janickiy/meet-space/internal/infrastructure/redis"
	"github.com/janickiy/meet-space/internal/infrastructure/security"
	s3 "github.com/janickiy/meet-space/internal/infrastructure/storage/s3"
	httptransport "github.com/janickiy/meet-space/internal/transport/http"
	middleware "github.com/janickiy/meet-space/internal/transport/http/middleware"
	ws "github.com/janickiy/meet-space/internal/transport/websocket"
	authusecase "github.com/janickiy/meet-space/internal/usecase/auth"
	chatusecase "github.com/janickiy/meet-space/internal/usecase/chat"
	conferencesusecase "github.com/janickiy/meet-space/internal/usecase/conferences"
	contentusecase "github.com/janickiy/meet-space/internal/usecase/content"
	foldersusecase "github.com/janickiy/meet-space/internal/usecase/folders"
	integrationsusecase "github.com/janickiy/meet-space/internal/usecase/integrations"
	notificationsusecase "github.com/janickiy/meet-space/internal/usecase/notifications"
	personalusecase "github.com/janickiy/meet-space/internal/usecase/personal"
	platformusecase "github.com/janickiy/meet-space/internal/usecase/platform"
	"github.com/janickiy/meet-space/internal/usecase/recorder"
	recordingsusecase "github.com/janickiy/meet-space/internal/usecase/recordings"
	pgdriver "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type settings struct{ DSN, JWTSecret, Certificate, PrivateKey, Listen, Redis, MinIO, MinIOAccess, MinIOSecret, StorageListen, StorageOrigin string }

func main() {
	if len(os.Args) != 2 {
		panic("fixture settings path required")
	}
	raw, err := os.ReadFile(os.Args[1])
	must(err)
	var fixture settings
	must(json.Unmarshal(raw, &fixture))
	ctx := context.Background()
	db, err := gorm.Open(pgdriver.Open(fixture.DSN), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	must(err)
	must(postgres.RunMigrations(db, "database/migrations"))
	tokens, err := security.NewTokenService(fixture.JWTSecret)
	must(err)
	users := postgres.NewUserRepository(db)
	auth, err := authusecase.NewService(users, security.PasswordHasher{}, tokens)
	must(err)
	auth.WithSessions(postgres.NewAuthSessionRepository(db))
	conferences := conferencesusecase.NewService(postgres.NewConferenceRepository(db), users, security.GenerateInviteCode)
	gin.SetMode(gin.ReleaseMode)
	router := httptransport.NewRouter()
	router.Use(gin.Recovery())
	must(router.SetTrustedProxies(nil))
	authentication := middleware.Authenticate(auth)
	httptransport.RegisterPlatformRoutes(router, authapp.NewHandler(auth).WithSessionCookies("https://"+fixture.Listen, true), conferencesapp.NewHandler(conferences), authentication)
	capabilities := &platformapp.Handler{Service: &platformusecase.Service{}, BuildVersion: "stage08"}
	router.GET("/api/v1/capabilities", authentication, capabilities.Capabilities)
	os.Setenv("APP_ENV", "local")
	os.Setenv("JWT_SECRET", fixture.JWTSecret)
	os.Setenv("WS_ALLOWED_ORIGINS", "https://10.0.2.2:18488,https://127.0.0.1:18488")
	rt, err := config.LoadRealtime()
	must(err)
	rt.Namespace = "android-stage08"
	redis, err := redisinfra.NewClient(ctx, fixture.Redis, "", 0)
	must(err)
	defer redis.Close()
	limiter := redisinfra.NewRateLimiter(redis)
	httptransport.RegisterInvitationRoutes(router, &conferencesapp.InvitationHandler{Service: &conferencesusecase.InvitationService{Repository: postgres.NewConferenceInvitationRepository(db)}}, authentication, limiter)
	bus := redisinfra.NewNotificationBus(redis, rt.Namespace)
	storage, err := s3.NewClient(ctx, fixture.MinIO, fixture.MinIOAccess, fixture.MinIOSecret, "meetspace-stage08", false)
	must(err)
	// TLS terminates here; only the isolated loopback hop to our own MinIO is HTTP.
	target, err := url.Parse("http://" + fixture.MinIO)
	must(err)
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) { http.Error(w, "storage unavailable", 502) }
	storageServer := &http.Server{Addr: fixture.StorageListen, Handler: proxy, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second}
	go func() { must(storageServer.ListenAndServeTLS(fixture.Certificate, fixture.PrivateKey)) }()
	storage.SetPublicEndpoint(fixture.StorageOrigin)
	// Only the actual authorized read/control services are assembled. No capture or
	// provider success is simulated; deterministic source artifacts are seeded separately.
	recordReader := recorder.NewService(postgres.NewRecordRepository(db), nil, storage, nil)
	recordingService := recordingsusecase.NewConferenceService(postgres.NewConferenceRecordingRepository(db), recordReader, nil)
	httptransport.RegisterConferenceRecordingRoutes(router, recordingsapp.NewHandler(recordingService), authentication)
	controls := conferencesusecase.NewControlService(postgres.NewConferenceRepository(db), nil, nil)
	httptransport.RegisterControlRoutes(router, conferencesapp.NewControlHandler(controls), authentication)
	contentConfig := contentusecase.DefaultConfig()
	stt, err := contentproviders.NewTranscriptionProvider("noop", "", "", contentConfig.STTTimeout)
	must(err)
	ai, err := contentproviders.NewAIProvider("noop", "", "", "", contentConfig.AITimeout)
	must(err)
	contentService, err := contentusecase.NewService(postgres.NewContentRepository(db), nil, stt, ai, contentConfig)
	must(err)
	httptransport.RegisterContentRoutes(router, contentapp.NewHandler(contentService), authentication, limiter)
	personalRepo := postgres.NewPersonalRepository(db)
	events := &personalusecase.Events{Members: personalRepo, Bus: bus}
	assets, err := personalusecase.NewAssetService(ctx, postgres.NewPersonalAssetRepository(db), storage, events)
	must(err)
	chat, err := chatusecase.NewService(ctx, postgres.NewDirectChatRepository(db), storage, events)
	must(err)
	presence := redisinfra.NewUserPresence(redis, rt.Namespace, rt.SessionTTL)
	globalWS := ws.NewUserHandler(auth, redisinfra.NewRealtimeStore(redis, rt.Namespace+":user-ws"), limiter, rt, bus, presence, personalRepo)
	defer globalWS.Shutdown()
	globalWS.RegisterRoutes(router)
	personal := &personalapp.Handler{Repo: personalRepo, Events: events, Presence: presence}
	httptransport.RegisterPersonalRoutes(router, personal, chatapp.NewHandler(chat).ForConversations().WithGroupDownloads(assets), authentication, limiter)
	httptransport.RegisterPersonalAssetRoutes(router, personalapp.NewAssetHandler(assets), authentication, personal.AccountOnly, limiter)
	httptransport.RegisterFolderRoutes(router, foldersapp.NewHandler(postgres.NewFolderRepository(db), &foldersusecase.Events{Bus: bus}), authentication, limiter)
	notifications := notificationsusecase.NewService(postgres.NewNotificationRepository(db).DisableLegacyReminders(), bus)
	httptransport.RegisterNotificationRoutes(router, notificationsapp.NewHandler(notifications, bus, auth, limiter, rt.Namespace), authentication)
	adapters, err := providers.NewIntegrations(providers.IntegrationConfig{Email: providers.AdapterConfig{Mode: "noop"}, Push: providers.AdapterConfig{Mode: "noop"}, Calendar: providers.AdapterConfig{Mode: "noop"}})
	must(err)
	integrations, err := integrationsusecase.NewService(postgres.NewIntegrationRepository(db), adapters, nil, integrationsusecase.Options{PublicURL: "https://" + fixture.Listen})
	must(err)
	httptransport.RegisterIntegrationRoutes(router, integrationsapp.NewHandler(integrations), authentication, limiter)
	go notifications.Run(ctx)
	go chat.Run(ctx)
	go func() {
		for {
			data, _ := json.Marshal(map[string]any{"userWebSocketConnections": globalWS.LocalCount()})
			_ = os.WriteFile(filepath.Join(filepath.Dir(os.Args[1]), "snapshot.json"), data, 0600)
			time.Sleep(time.Second)
		}
	}()
	buildinfo.Version = "stage08"
	buildinfo.Commit = "6ecd8b648a2aa94fc9bcc844d57784423e2c6f69"
	buildinfo.BuildTime = "2026-10-11T00:00:00Z"
	router.GET("/version", gin.WrapF(buildinfo.Handler))
	server := &http.Server{Addr: fixture.Listen, Handler: router, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 60 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 32 * 1024}
	fmt.Println("Disposable stage08 validated HTTPS fixture ready")
	must(server.ListenAndServeTLS(fixture.Certificate, fixture.PrivateKey))
}
func must(err error) {
	if err != nil {
		panic("stage08 fixture setup failed")
	}
}
