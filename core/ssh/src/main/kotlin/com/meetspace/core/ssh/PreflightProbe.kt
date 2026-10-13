package com.meetspace.core.ssh

/** Fixed read-only probe. No user-supplied shell program, sudo password, package install or config read. */
object PreflightProbe {
    fun command(domain: String, host: String): String {
        val request = SshInputValidator.validate(host, "22", "probe", domain)
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return "LC_ALL=C sh -s -- ${quote(request.domain)} ${quote(request.endpoint.host)}"
    }

    val script: String = """
        emit() { printf 'MSPF1\t%s\t%s\n' "${'$'}1" "${'$'}2"; }
        limited() { if command -v timeout >/dev/null 2>&1; then timeout 4 "${'$'}@"; else return 125; fi; }
        domain=${'$'}1
        ssh_host=${'$'}2
        emit protocol 1
        emit kernel "${'$'}(uname -s 2>/dev/null || printf unknown)"
        emit arch "${'$'}(uname -m 2>/dev/null || printf unknown)"
        if [ -r /etc/os-release ]; then
          emit distro "${'$'}(awk -F= '/^ID=/{gsub(/"/,"",${'$'}2); print ${'$'}2; exit}' /etc/os-release)"
          emit distro_version "${'$'}(awk -F= '/^VERSION_ID=/{gsub(/"/,"",${'$'}2); print ${'$'}2; exit}' /etc/os-release)"
        else emit distro unknown; emit distro_version unknown; fi
        emit cpu "${'$'}(getconf _NPROCESSORS_ONLN 2>/dev/null || printf unknown)"
        emit ram_kib "${'$'}(awk '/^MemTotal:/{print ${'$'}2; exit}' /proc/meminfo 2>/dev/null)"
        emit disk_kib "${'$'}(df -Pk / 2>/dev/null | awk 'NR==2 {print ${'$'}4}')"
        uid=${'$'}(id -u 2>/dev/null)
        emit uid "${'$'}uid"
        if [ "${'$'}uid" = 0 ]; then emit sudo root
        elif command -v sudo >/dev/null 2>&1 && limited sudo -n true >/dev/null 2>&1; then emit sudo noninteractive
        else emit sudo unavailable; fi
        if command -v docker >/dev/null 2>&1; then
          emit docker "${'$'}(limited docker --version 2>/dev/null | awk '{gsub(/,/,"",${'$'}3); print ${'$'}3; exit}')"
          if limited docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then emit docker_access yes; else emit docker_access no; fi
          compose=${'$'}(limited docker compose version --short 2>/dev/null)
          if [ -n "${'$'}compose" ]; then emit compose "${'$'}compose"; else emit compose missing; fi
          if limited docker ps -a --format '{{.Names}}' 2>/dev/null | grep -Eiq '(meetspace|meetrix|meeting)'; then emit existing_containers yes; else emit existing_containers unknown; fi
        else emit docker missing; emit docker_access unknown; emit compose missing; emit existing_containers unknown; fi
        if [ -d /opt/meetrix ] || [ -d /etc/meetrix ] || [ -d /opt/meetspace ] || [ -d /etc/meetspace ]; then emit existing_paths yes; else emit existing_paths no; fi
        tcp_ports=''
        udp_ports=''
        if command -v ss >/dev/null 2>&1 && tcp_rows=${'$'}(limited ss -H -ltn 2>/dev/null) && udp_rows=${'$'}(limited ss -H -lun 2>/dev/null); then
          emit ports_known yes
          tcp_ports=${'$'}(printf '%s\n' "${'$'}tcp_rows" | awk '{p=${'$'}4; sub(/^.*:/,"",p); if(p ~ /^[0-9]+${'$'}/) print p}')
          udp_ports=${'$'}(printf '%s\n' "${'$'}udp_rows" | awk '{p=${'$'}4; sub(/^.*:/,"",p); if(p ~ /^[0-9]+${'$'}/) print p}')
        else emit ports_known no; fi
        for p in 80 443 3478 5349 50010; do
          if printf '%s\n' "${'$'}tcp_ports" | grep -qx "${'$'}p"; then emit "tcp_${'$'}p" busy; else emit "tcp_${'$'}p" free; fi
        done
        for p in 3478 50010; do
          if printf '%s\n' "${'$'}udp_ports" | grep -qx "${'$'}p"; then emit "udp_${'$'}p" busy; else emit "udp_${'$'}p" free; fi
        done
        emit udp_relay_busy "${'$'}(printf '%s\n' "${'$'}udp_ports" | awk '${'$'}1 >= 49160 && ${'$'}1 <= 49259 {n++} END {print n+0}')"
        if command -v getent >/dev/null 2>&1; then
          emit dns_domain "${'$'}(limited getent ahosts "${'$'}domain" 2>/dev/null | awk '{print ${'$'}1}' | sort -u | head -8 | paste -sd, -)"
          emit dns_ssh "${'$'}(limited getent ahosts "${'$'}ssh_host" 2>/dev/null | awk '{print ${'$'}1}' | sort -u | head -8 | paste -sd, -)"
        else emit dns_domain unknown; emit dns_ssh unknown; fi
        if command -v ufw >/dev/null 2>&1 && limited ufw status 2>/dev/null | grep -q '^Status: active'; then emit firewall ufw_active
        elif command -v nft >/dev/null 2>&1 && limited nft list ruleset >/dev/null 2>&1; then emit firewall nft_readable
        else emit firewall unknown; fi
        emit complete 1
    """.trimIndent() + "\n"
}
