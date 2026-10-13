// Disposable Android stage05 fixture. All business routes use the audited backend.
package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/gin-gonic/gin"
	authapp "github.com/janickiy/meet-space/internal/app/auth"
	conferencesapp "github.com/janickiy/meet-space/internal/app/conferences"
	platformapp "github.com/janickiy/meet-space/internal/app/platform"
	"github.com/janickiy/meet-space/internal/buildinfo"
	"github.com/janickiy/meet-space/internal/infrastructure/postgres"
	"github.com/janickiy/meet-space/internal/infrastructure/security"
	httptransport "github.com/janickiy/meet-space/internal/transport/http"
	middleware "github.com/janickiy/meet-space/internal/transport/http/middleware"
	authusecase "github.com/janickiy/meet-space/internal/usecase/auth"
	conferencesusecase "github.com/janickiy/meet-space/internal/usecase/conferences"
	platformusecase "github.com/janickiy/meet-space/internal/usecase/platform"
	pgdriver "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type settings struct{ DSN, JWTSecret, Certificate, PrivateKey, Listen string }

func main() {
	if len(os.Args) != 2 {
		panic("fixture settings path required")
	}
	raw, err := os.ReadFile(os.Args[1])
	must(err)
	var config settings
	must(json.Unmarshal(raw, &config))
	if config.Listen == "" {
		config.Listen = "127.0.0.1:18455"
	}
	db, err := gorm.Open(pgdriver.Open(config.DSN), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	must(err)
	must(postgres.RunMigrations(db, "database/migrations"))
	tokens, err := security.NewTokenService(config.JWTSecret)
	must(err)
	users := postgres.NewUserRepository(db)
	auth, err := authusecase.NewService(users, security.PasswordHasher{}, tokens)
	must(err)
	auth.WithSessions(postgres.NewAuthSessionRepository(db))
	authHandler := authapp.NewHandler(auth).WithSessionCookies("https://"+config.Listen, true)
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
	capabilities := &platformapp.Handler{Service: &platformusecase.Service{}, BuildVersion: "stage05"}
	router.GET("/api/v1/capabilities", authentication, capabilities.Capabilities)
	buildinfo.Version = "stage05"
	buildinfo.Commit = "6ecd8b648a2aa94fc9bcc844d57784423e2c6f69"
	buildinfo.BuildTime = "2026-10-11T00:00:00Z"
	router.GET("/version", gin.WrapF(buildinfo.Handler))
	server := &http.Server{Addr: config.Listen, Handler: router, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 32 * 1024}
	fmt.Println("Disposable stage05 HTTPS fixture ready")
	must(server.ListenAndServeTLS(config.Certificate, config.PrivateKey))
}

func must(err error) {
	if err != nil {
		panic("stage05 fixture setup failed")
	}
}
