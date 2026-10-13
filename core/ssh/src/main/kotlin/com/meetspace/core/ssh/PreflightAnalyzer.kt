package com.meetspace.core.ssh

import com.meetspace.core.model.*
import java.net.InetAddress

object PreflightAnalyzer {
    private val fields = setOf("protocol", "kernel", "arch", "distro", "distro_version", "cpu", "ram_kib", "disk_kib", "uid", "sudo", "docker", "docker_access", "compose", "existing_containers", "existing_paths", "ports_known", "tcp_80", "tcp_443", "tcp_3478", "tcp_5349", "tcp_50010", "udp_3478", "udp_50010", "udp_relay_busy", "dns_domain", "dns_ssh", "firewall", "complete")

    fun analyze(request: SshRequest, hostKey: SshHostKey, stdout: String, network: NetworkReadiness = NetworkReadiness()): PreflightReport {
        val verified = SshInputValidator.validateRequest(request)
        val data = parse(stdout)
        val checks = mutableListOf<PreflightCheck>()
        fun add(id: String, title: String, level: PreflightLevel, detail: String) { checks += PreflightCheck(id, title, level, detail) }
        val distro = data.getValue("distro")
        val version = data.getValue("distro_version")
        val supported = data["kernel"] == "Linux" && ((distro == "ubuntu" && version in setOf("22.04", "24.04")) || (distro == "debian" && version.substringBefore('.') in setOf("12", "13")))
        add("os", "Операционная система", if (supported) PreflightLevel.PASS else PreflightLevel.BLOCKED,
            if (supported) "$distro $version, Linux. Подходит для предварительной проверки; матрица установщика ещё не утверждена." else "Платформа $distro $version (${data["kernel"]}) вне предварительной матрицы Ubuntu 22.04/24.04 и Debian 12/13.")
        val arch = data.getValue("arch")
        add("arch", "Архитектура", if (arch in setOf("aarch64", "arm64", "x86_64", "amd64")) PreflightLevel.PASS else PreflightLevel.BLOCKED,
            "$arch. Для будущей установки потребуется release именно этой архитектуры.")
        val cpu = number(data, "cpu")
        add("cpu", "CPU", when { cpu == null -> PreflightLevel.UNKNOWN; cpu < 4 -> PreflightLevel.WARN; else -> PreflightLevel.PASS },
            cpu?.let { "$it логических CPU. Ориентир полного стека: от 4; это не гарантия вместимости медианагрузки." } ?: "Не удалось определить число CPU.")
        fun resource(id: String, field: String, title: String, thresholdGiB: Long) {
            val kib = number(data, field)
            add(id, title, when { kib == null -> PreflightLevel.UNKNOWN; kib < thresholdGiB * 1_048_576L -> PreflightLevel.WARN; else -> PreflightLevel.PASS },
                kib?.let { "${"%.1f".format(java.util.Locale.ROOT, it / 1_048_576.0)} GiB. Предварительный ориентир: от $thresholdGiB GiB; будущий план должен учесть профиль и записи." } ?: "Ресурс не удалось определить.")
        }
        resource("ram", "ram_kib", "Оперативная память", 8)
        resource("disk", "disk_kib", "Свободное место в /", 30)
        val uid = data.getValue("uid")
        add("sudo", "Права пользователя", when (data["sudo"]) { "root" -> PreflightLevel.WARN; "noninteractive" -> PreflightLevel.PASS; else -> PreflightLevel.WARN },
            when (data["sudo"]) { "root" -> "UID $uid: root-доступ. Будущий план должен явно перечислить привилегированные действия."; "noninteractive" -> "UID $uid: sudo -n true разрешён. Это не подтверждает разрешение всех команд установки; пароль в sudo не передаётся."; else -> "UID $uid: noninteractive sudo не подтверждён. Настройка прав требует отдельного плана." })
        val docker = data.getValue("docker")
        val dockerPresent = isVersion(docker)
        add("docker", "Docker", if (dockerPresent) PreflightLevel.PASS else PreflightLevel.WARN,
            if (dockerPresent) "Docker $docker обнаружен. Совместимая версия будет определена будущим планом." else "Docker не обнаружен или версию не удалось проверить. Установка не выполнялась.")
        add("docker-access", "Доступ к Docker daemon", if (data["docker_access"] == "yes") PreflightLevel.WARN else PreflightLevel.UNKNOWN,
            if (data["docker_access"] == "yes") "Daemon доступен. Этот доступ обычно равносилен root; будущий план должен учитывать эту границу прав." else "Доступ не подтверждён; отсутствие ответа не означает отсутствие контейнеров.")
        val compose = data.getValue("compose")
        add("compose", "Docker Compose v2", if (isVersion(compose) && compose.removePrefix("v").substringBefore('.') == "2") PreflightLevel.PASS else PreflightLevel.WARN,
            if (isVersion(compose)) "Обнаружена версия $compose; для production нужен совместимый Compose v2." else "Compose v2 не обнаружен или проверка недоступна. Ничего не устанавливалось.")
        val existing = data["existing_paths"] == "yes" || data["existing_containers"] == "yes"
        add("existing-install", "Существующая установка", if (existing) PreflightLevel.BLOCKED else PreflightLevel.UNKNOWN,
            if (existing) "Есть каталоги или контейнеры с именами MeetSpace/Meetrix/meeting. Нужны отдельный план обновления и backup; файлы и контейнеры не менялись." else "В известных путях установка не обнаружена. Другие пути, volumes и недоступный Docker не проверены; отсутствие установки не доказано.")
        val portsKnown = data["ports_known"] == "yes"
        for (field in listOf("tcp_80", "tcp_443", "tcp_3478", "tcp_5349", "tcp_50010", "udp_3478", "udp_50010")) {
            val protocol = field.substringBefore('_').uppercase()
            val port = field.substringAfter('_')
            val busy = data[field] == "busy"
            add("port-$field", "Порт $port/$protocol", when { !portsKnown -> PreflightLevel.UNKNOWN; busy && field == "tcp_80" -> PreflightLevel.WARN; busy -> PreflightLevel.BLOCKED; else -> PreflightLevel.PASS },
                when { !portsKnown -> "ss недоступен: состояние порта неизвестно."; busy -> "Есть локальный listener. Необходим план proxy/совместного размещения; существующий сервис не останавливался."; else -> "Локальный listener не обнаружен. Внешняя доступность через firewall/NAT этим не доказана." })
        }
        val relay = number(data, "udp_relay_busy", allowZero = true)
        add("port-udp-relay", "TURN relay 49160–49259/UDP", when { !portsKnown || relay == null -> PreflightLevel.UNKNOWN; relay > 0 -> PreflightLevel.BLOCKED; else -> PreflightLevel.PASS },
            if (portsKnown && relay != null) "В диапазоне найдено $relay локальных listeners. Это диапазон текущего production Compose; внешний UDP не проверен." else "Состояние relay-диапазона неизвестно.")
        val domainIps = (ips(data.getValue("dns_domain")) + ips(network.addresses.joinToString(","))).distinct()
        val sshIps = ips(data.getValue("dns_ssh"))
        val match = domainIps.any { it in sshIps }
        add("dns", "DNS домена", when { domainIps.isEmpty() -> PreflightLevel.WARN; match -> PreflightLevel.PASS; else -> PreflightLevel.WARN },
            when { domainIps.isEmpty() -> "Домен пока не разрешается или lookup недоступен. Настройте DNS перед будущим HTTPS запуском."; match -> "Есть IP, совпадающий с SSH host. Проверьте все A/AAAA, proxy и NAT; это не проверка ACME challenge."; else -> "DNS отвечает, но совпадение с IP SSH host не подтверждено. Возможны proxy/NAT; проверьте целевые A/AAAA." })
        add("tls", "TLS домена с Android", if (network.tls == TlsReadiness.VALID) PreflightLevel.PASS else PreflightLevel.WARN,
            when (network.tls) { TlsReadiness.VALID -> "HTTPS handshake прошёл с системным CA и проверкой имени. Это не подтверждает наличие MeetSpace или настройку renew."; TlsReadiness.INVALID_CERTIFICATE -> "Сертификат или HTTPS handshake не прошёл системную проверку. Обход TLS запрещён; сертификат не изменялся."; TlsReadiness.DNS_FAILURE -> "DNS с Android недоступен; TLS пока не проверен."; TlsReadiness.TIMEOUT -> "HTTPS не ответил в пределах timeout; готовность TLS не подтверждена."; else -> "HTTPS/TLS пока не подтверждён. Для нового сервера это ожидаемый пункт будущего плана." })
        add("firewall", "Firewall и внешняя сеть", PreflightLevel.UNKNOWN,
            when (data["firewall"]) { "ufw_active" -> "UFW активен. Правила не изменялись; cloud firewall/NAT и внешний TCP/UDP требуют отдельной проверки."; "nft_readable" -> "nftables доступен для чтения. Правила не изменялись; их корректность и cloud firewall/NAT не подтверждены."; else -> "Полная проверка firewall без изменения прав не выполнена. Проверьте host/cloud firewall, NAT и внешний TCP/UDP отдельно." })
        return PreflightReport(verified.endpoint, verified.domain, hostKey, checks, System.currentTimeMillis())
    }

    private fun parse(stdout: String): Map<String, String> {
        if (stdout.toByteArray().size > 65_536) invalid()
        val result = linkedMapOf<String, String>()
        val lines = stdout.lineSequence().filter(String::isNotEmpty).toList()
        if (lines.size != fields.size) invalid()
        for (line in lines) {
            if (line.length > 600 || line.any { it.isISOControl() && it != '\t' }) invalid()
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0] != "MSPF1" || parts[1] !in fields || result.containsKey(parts[1])) invalid()
            val value = parts[2]
            if (value.length > 512 || !value.matches(Regex("[a-zA-Z0-9.,:_+/-]*"))) invalid()
            result[parts[1]] = value
        }
        if (result.keys != fields || result["protocol"] != "1" || result["complete"] != "1") invalid()
        for (field in listOf("tcp_80", "tcp_443", "tcp_3478", "tcp_5349", "tcp_50010", "udp_3478", "udp_50010")) if (result[field] !in setOf("busy", "free")) invalid()
        if (result["sudo"] !in setOf("root", "noninteractive", "unavailable") || result["ports_known"] !in setOf("yes", "no")) invalid()
        return result
    }
    private fun number(data: Map<String, String>, key: String, allowZero: Boolean = false): Long? = data.getValue(key).toLongOrNull()?.takeIf { it >= (if (allowZero) 0 else 1) && it <= 1_000_000_000_000L }
    private fun isVersion(value: String) = value.matches(Regex("v?[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+][a-zA-Z0-9.]+)?"))
    private fun ips(value: String): List<String> = value.split(',').filter { it.isNotEmpty() && it != "unknown" }.mapNotNull {
        val numeric = if (it.contains(':')) it.matches(Regex("[0-9a-fA-F:]+"))
            else it.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255 } }
        if (!numeric) return@mapNotNull null
        runCatching { InetAddress.getByName(it).hostAddress }.getOrNull()
    }
    private fun invalid(): Nothing = throw SshFailure(SshFailureKind.INVALID_RESPONSE, "Сервер вернул неполный или некорректный результат проверки. Готовность не подтверждена.")
}
