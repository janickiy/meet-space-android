# JSch resolves crypto/authentication implementations by configured class names. Keep those
# names and constructors, while excluding optional desktop agents, Kerberos and logger bridges.
# This app uses only password/publickey auth, a silent logger and a plain TCP socket factory.
-keep class !com.jcraft.jsch.PageantConnector,!com.jcraft.jsch.SSHAgentConnector,!com.jcraft.jsch.JUnixSocketFactory,!com.jcraft.jsch.UnixDomainSocketFactory,!com.jcraft.jsch.jgss.**,!com.jcraft.jsch.UserAuthGSSAPIWithMIC,!com.jcraft.jsch.Log4j2Logger,!com.jcraft.jsch.Slf4jLogger,com.jcraft.jsch.** { *; }
