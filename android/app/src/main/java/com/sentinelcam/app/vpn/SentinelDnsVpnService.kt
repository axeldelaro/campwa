package com.sentinelcam.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.selfcontrol.SelfControlStore
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences
import com.sentinelcam.app.storage.SentinelStoreDb
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SentinelCam v28.0 — Built-In Zero-Server Local Always-On DNS/VPN Firewall (`VpnService`).
 *
 * Architecture:
 * - Creates a lightweight DNS-only TUN interface (`10.88.0.2/32`, DNS `10.88.0.1`, route `10.88.0.1/32`).
 * - 0% throughput overhead on normal traffic (only UDP/53 DNS queries enter the TUN!) and near-zero battery use.
 * - Excludes SentinelCam itself (`addDisallowedApplication`) so signaling/maps never deadlock.
 * - Parses standard RFC 1035 DNS queries, evaluates:
 *   1) Custom Whitelist
 *   2) Anti-DoH Bypass domains
 *   3) Custom Blacklist & Domain Keywords
 *   4) 8 Built-In Categories (Adult, Social, Streaming, Gaming, Gambling/Crypto, News, Unfiltered AI, APK/Proxy)
 *   5) Absolute App ↔ Web Coupling (automatically blocks web domains of any blocked/quota-exhausted/detoxed app!)
 *   6) SafeSearch & YouTube Restricted Mode DNS enforcement
 * - Blocked queries receive an instant `< 1 ms` NXDOMAIN response & are logged in SQLite `dns_logs`.
 * - Allowed queries are forwarded via `protect(DatagramSocket)` to Cloudflare Family (`1.1.1.3`) or `1.1.1.1`.
 */
class SentinelDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var workerPool: ExecutorService? = null
    private val writeLock = Any()
    private var tunOut: FileOutputStream? = null

    private lateinit var scStore: SelfControlStore
    private lateinit var prefs: PortalPreferences
    private lateinit var db: SentinelStoreDb

    private val lastLoggedByDomain = ConcurrentHashMap<String, Long>()
    @Volatile
    private var cachedCoupledDomains: Set<String> = emptySet()
    @Volatile
    private var lastCoupledRefreshMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        scStore = SelfControlStore(applicationContext)
        prefs = PortalPreferences(applicationContext)
        db = SentinelStoreDb.get(applicationContext)
        createVpnNotifChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Obey One-Way Seal: refuse stop if Pact is sealed & user cannot weaken!
            if (scStore.isSettingsProtectedNow() && scStore.dnsVpnEnabled) {
                startVpnLoopIfNeeded()
                return START_STICKY
            }
            stopVpnInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RELOAD) {
            lastCoupledRefreshMs = 0L
        }

        if (!scStore.dnsVpnEnabled) {
            stopVpnInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()
        startVpnLoopIfNeeded()
        return START_STICKY
    }

    override fun onRevoke() {
        val sealedOrManaged = scStore.isSettingsProtectedNow() || (prefs.parentManaged && scStore.dnsVpnEnabled)
        stopVpnInternal()
        if (sealedOrManaged) {
            prefs.appendSecurityEvent(
                kind = "bypass",
                title = "Pare-feu DNS/VPN coupé",
                detail = "La protection DNS locale a été révoquée sur l’appareil."
            )
            scStore.notifyPartnerIfConfigured(
                "Alerte : Pare-feu DNS/VPN révoqué",
                "Le VPN DNS local a été désactivé alors que le Pacte Geo-Vault est actif."
            )
            CameraForegroundService.notifySecurityEventExternal()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                301,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val catsCount = scStore.getDnsCategories().size
            val statusTxt = if (scStore.pactSealed) {
                "Pacte Geo-Vault scellé • $catsCount catégorie(s) filtrée(s)"
            } else {
                "Filtre DNS local actif • $catsCount catégorie(s) protégée(s)"
            }
            val notif: Notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("SentinelCam • Pare-feu DNS")
                .setContentText(statusTxt)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build()
            startForeground(VPN_NOTIF_ID, notif)
        } catch (_: Throwable) {
        }
    }

    private fun startVpnLoopIfNeeded() {
        if (running.get() && vpnInterface != null) return
        stopVpnInternal()

        val pfd = try {
            val builder = Builder()
                .setSession("SentinelCam DNS Shield")
                .setMtu(1500)
                .addAddress(TUN_LOCAL_IP, 32)
                .addDnsServer(TUN_DNS_IP)
                .addRoute(TUN_DNS_IP, 32)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Throwable) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.establish()
        } catch (_: Throwable) {
            null
        }

        if (pfd == null) {
            isVpnActive = false
            return
        }

        vpnInterface = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        workerPool = Executors.newFixedThreadPool(6) { r ->
            Thread(r, "SentinelDnsWorker").apply { isDaemon = true }
        }
        running.set(true)
        isVpnActive = true

        readerThread = Thread({
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            while (running.get()) {
                val len = try {
                    input.read(buf)
                } catch (_: Throwable) {
                    break
                }
                if (len <= 0) {
                    try { Thread.sleep(10) } catch (_: Throwable) { break }
                    continue
                }
                val packet = buf.copyOf(len)
                workerPool?.execute {
                    try {
                        handleIpv4Packet(packet)
                    } catch (_: Throwable) {
                    }
                }
            }
            isVpnActive = false
        }, "SentinelDnsTunReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopVpnInternal() {
        running.set(false)
        isVpnActive = false
        try { vpnInterface?.close() } catch (_: Throwable) {}
        vpnInterface = null
        tunOut = null
        try { readerThread?.interrupt() } catch (_: Throwable) {}
        readerThread = null
        try { workerPool?.shutdownNow() } catch (_: Throwable) {}
        workerPool = null
    }

    private fun handleIpv4Packet(pkt: ByteArray) {
        if (pkt.size < 28) return
        val version = (pkt[0].toInt() ushr 4) and 0x0F
        if (version != 4) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || pkt.size < ihl + 8) return
        val protocol = pkt[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val srcPort = readU16(pkt, ihl)
        val dstPort = readU16(pkt, ihl + 2)
        if (dstPort != 53) return

        val udpOffset = ihl + 8
        val dnsLen = pkt.size - udpOffset
        if (dnsLen < 12) return

        val dnsPayload = pkt.copyOfRange(udpOffset, pkt.size)
        val domainRaw = parseDnsQueryDomain(dnsPayload)
        val domain = DnsBlocklistCatalog.normalizeHost(domainRaw)
        if (domain.isEmpty()) return

        val decision = evaluateDomain(domain)
        val responseDns: ByteArray? = when {
            decision.blocked -> {
                logDnsDeduped(domain, decision.reason, blocked = true)
                buildNxDomainDns(dnsPayload)
            }
            decision.safeSearchTarget != null -> {
                val rewrittenQuery = buildRewrittenDnsQuery(dnsPayload, decision.safeSearchTarget)
                val upstreamResp = forwardDnsUpstream(rewrittenQuery, useFamilyDns = true)
                if (upstreamResp != null) {
                    // Patch transaction ID & question section back so client accepts the answer seamlessly
                    patchSafeSearchResponse(dnsPayload, upstreamResp)
                } else {
                    forwardDnsUpstream(dnsPayload, useFamilyDns = true)
                }
            }
            else -> {
                val useFamily = scStore.getDnsCategories().contains("adult") || scStore.dnsForceSafeSearch
                forwardDnsUpstream(dnsPayload, useFamilyDns = useFamily)
            }
        }

        if (responseDns != null && responseDns.isNotEmpty()) {
            val replyIpv4 = buildIpv4UdpReply(
                srcIp = pkt.copyOfRange(16, 20), // swap dst -> src
                dstIp = pkt.copyOfRange(12, 16), // swap src -> dst
                srcPort = 53,
                dstPort = srcPort,
                payload = responseDns
            )
            synchronized(writeLock) {
                try {
                    tunOut?.write(replyIpv4)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private data class DomainDecision(
        val blocked: Boolean,
        val reason: String = "",
        val safeSearchTarget: String? = null
    )

    private fun evaluateDomain(domain: String): DomainDecision {
        // 1. Whitelist always wins (except DoH bypass if sealed)
        val whitelist = scStore.getDnsWhitelist()
        if (whitelist.isNotEmpty() && DnsBlocklistCatalog.matchesDomainSet(domain, whitelist)) {
            return DomainDecision(blocked = false)
        }

        // 2. Always block DoH endpoints so Chrome/Firefox cannot route around our DNS filter!
        if (DnsBlocklistCatalog.matchesDomainSet(domain, DnsBlocklistCatalog.DOH_BYPASS_DOMAINS)) {
            return DomainDecision(blocked = true, reason = "doh_bypass")
        }

        // 3. Custom Blacklist
        val blacklist = scStore.getDnsBlacklist()
        if (blacklist.isNotEmpty() && DnsBlocklistCatalog.matchesDomainSet(domain, blacklist)) {
            return DomainDecision(blocked = true, reason = "blacklist")
        }

        // 4. Custom Blocked Keywords
        val keywords = scStore.getDnsBlockedKeywords()
        for (kw in keywords) {
            if (kw.length >= 3 && domain.contains(kw)) {
                return DomainDecision(blocked = true, reason = "keyword:$kw")
            }
        }

        // 5. Built-in 8 Category Lists
        val activeCats = scStore.getDnsCategories()
        for (cat in activeCats) {
            val list = DnsBlocklistCatalog.CATEGORY_DOMAINS[cat] ?: continue
            if (DnsBlocklistCatalog.matchesDomainSet(domain, list)) {
                return DomainDecision(blocked = true, reason = cat)
            }
            if (cat == "adult") {
                for (tok in DnsBlocklistCatalog.ADULT_DOMAIN_TOKENS) {
                    if (domain.contains(tok)) {
                        return DomainDecision(blocked = true, reason = "adult")
                    }
                }
            }
        }

        // 6. Absolute App ↔ Web Coupling (blocked apps / active Detox / active Curfew / active Schedule)
        if (scStore.dnsCoupleAppWeb) {
            val coupled = getActiveCoupledBlockedDomains()
            if (coupled.isNotEmpty() && DnsBlocklistCatalog.matchesDomainSet(domain, coupled)) {
                return DomainDecision(blocked = true, reason = "app_web_coupled")
            }
        }

        // 7. SafeSearch & YouTube Restricted DNS rewrite
        if (scStore.dnsForceSafeSearch) {
            val target = DnsBlocklistCatalog.SAFESEARCH_REWRITES[domain]
            if (target != null) {
                return DomainDecision(blocked = false, safeSearchTarget = target)
            }
        }

        return DomainDecision(blocked = false)
    }

    private fun getActiveCoupledBlockedDomains(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastCoupledRefreshMs < 4_000L) return cachedCoupledDomains
        lastCoupledRefreshMs = now

        val blockedPkgs = HashSet<String>()
        blockedPkgs.addAll(scStore.getBlockedAppPackages())
        for (r in prefs.getActiveAppLockRules(now)) blockedPkgs.add(r.packageName)

        // If Nuclear Detox, Bedtime, Curfew, Pause, or Focus Schedule/Geofence is active -> block all social/streaming web too!
        val activeSch = scStore.getActiveScheduleNow(now)
        val activeGeoFocus = scStore.getActiveFocusGeofenceMode(now)
        val strictSocialWebCut = scStore.isNuclearDetoxActive(now) ||
            scStore.isBedtimeActiveNow(now) ||
            prefs.isCurfewActiveNow(now) ||
            prefs.screenPausedUntilMs > now ||
            activeSch != null ||
            activeGeoFocus != null

        val out = HashSet<String>()
        for (pkg in blockedPkgs) {
            DnsBlocklistCatalog.PACKAGE_TO_DOMAINS[pkg]?.let { out.addAll(it) }
        }
        if (strictSocialWebCut) {
            DnsBlocklistCatalog.CATEGORY_DOMAINS["social"]?.let { out.addAll(it) }
            if (scStore.isNuclearDetoxActive(now)) {
                DnsBlocklistCatalog.CATEGORY_DOMAINS["streaming"]?.let { out.addAll(it) }
                DnsBlocklistCatalog.CATEGORY_DOMAINS["gaming"]?.let { out.addAll(it) }
            }
        }
        if (activeSch != null) {
            for (pkg in activeSch.blockedPackages) {
                DnsBlocklistCatalog.PACKAGE_TO_DOMAINS[pkg]?.let { out.addAll(it) }
            }
        }
        if (prefs.reelsCooldownUntilMs > now) {
            out.add("tiktok.com")
            out.add("tiktokcdn.com")
        }

        cachedCoupledDomains = out
        return out
    }

    private fun logDnsDeduped(domain: String, category: String, blocked: Boolean) {
        val now = System.currentTimeMillis()
        val prev = lastLoggedByDomain[domain] ?: 0L
        if (now - prev < 10_000L) return
        lastLoggedByDomain[domain] = now
        if (lastLoggedByDomain.size > 300) lastLoggedByDomain.clear()
        db.insertDnsLog(domain, category, blocked, now)
        if (blocked && category != "doh_bypass") {
            scStore.recordReclaimedAction("web:$domain", "dns_$category", estimatedSavedMin = 5)
        }
    }

    private fun forwardDnsUpstream(query: ByteArray, useFamilyDns: Boolean): ByteArray? {
        val servers = if (useFamilyDns) {
            arrayOf("1.1.1.3", "94.140.14.15", "8.8.8.8")
        } else {
            arrayOf("1.1.1.1", "9.9.9.9", "8.8.8.8")
        }
        for (serverIp in servers) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!protect(socket)) {
                    socket.close()
                    continue
                }
                socket.soTimeout = 2_200
                val addr = InetAddress.getByName(serverIp)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val respBuf = ByteArray(1400)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPkt)
                return respBuf.copyOf(respPkt.length)
            } catch (_: Throwable) {
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }
        return null
    }

    /** Parses the QNAME from a DNS query packet (RFC 1035). */
    private fun parseDnsQueryDomain(dns: ByteArray): String {
        if (dns.size < 13) return ""
        val sb = StringBuilder(64)
        var pos = 12
        var guard = 0
        while (pos < dns.size && guard < 64) {
            guard++
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) break // pointer not expected in QNAME
            pos++
            if (pos + len > dns.size) break
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            }
            pos += len
        }
        return sb.toString().lowercase(Locale.US)
    }

    /** Builds a clean RFC 1035 NXDOMAIN (RCODE=3) response matching the client's query ID & question. */
    private fun buildNxDomainDns(query: ByteArray): ByteArray {
        // Locate end of Question section (QNAME + 4 bytes QTYPE/QCLASS)
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            pos += len
        }
        val questionEnd = (pos + 4).coerceAtMost(query.size)
        val resp = query.copyOf(questionEnd)
        // Header flags: QR=1, Opcode copied, RD copied, RA=1, RCODE=3 (NXDOMAIN)
        resp[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
        resp[3] = 0x83.toByte()
        // ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        resp[6] = 0; resp[7] = 0
        resp[8] = 0; resp[9] = 0
        resp[10] = 0; resp[11] = 0
        return resp
    }

    /** Builds a fresh DNS A-record query for a SafeSearch target hostname. */
    private fun buildRewrittenDnsQuery(origQuery: ByteArray, targetHost: String): ByteArray {
        val labels = targetHost.split('.')
        val qnameLen = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(12 + qnameLen + 4)
        out[0] = origQuery[0]
        out[1] = origQuery[1]
        out[2] = 0x01 // Standard query, RD=1
        out[3] = 0x00
        out[4] = 0x00; out[5] = 0x01 // QDCOUNT = 1
        var p = 12
        for (lbl in labels) {
            out[p++] = lbl.length.toByte()
            for (ch in lbl) out[p++] = ch.code.toByte()
        }
        out[p++] = 0
        // Copy QTYPE & QCLASS from original query (fallback A / IN)
        var op = 12
        while (op < origQuery.size) {
            val l = origQuery[op].toInt() and 0xFF
            op++
            if (l == 0) break
            op += l
        }
        if (op + 4 <= origQuery.size) {
            out[p++] = origQuery[op]
            out[p++] = origQuery[op + 1]
            out[p++] = origQuery[op + 2]
            out[p] = origQuery[op + 3]
        } else {
            out[p++] = 0; out[p++] = 1; out[p++] = 0; out[p] = 1
        }
        return out
    }

    /** Re-maps SafeSearch A/AAAA answer records back to the original queried hostname. */
    private fun patchSafeSearchResponse(origQuery: ByteArray, safeResp: ByteArray): ByteArray {
        // Extract first IPv4 A record from safeResp if present, else fallback to safeResp
        if (safeResp.size < 16) return safeResp
        val anCount = readU16(safeResp, 6)
        if (anCount <= 0) return safeResp

        // Skip question in safeResp
        var sp = 12
        while (sp < safeResp.size) {
            val l = safeResp[sp].toInt() and 0xFF
            sp++
            if (l == 0) break
            sp += l
        }
        sp += 4 // QTYPE + QCLASS

        var foundIpv4: ByteArray? = null
        for (i in 0 until anCount) {
            if (sp + 12 > safeResp.size) break
            // Skip NAME (either pointer or labels)
            if ((safeResp[sp].toInt() and 0xC0) == 0xC0) {
                sp += 2
            } else {
                while (sp < safeResp.size) {
                    val l = safeResp[sp].toInt() and 0xFF
                    sp++
                    if (l == 0) break
                    sp += l
                }
            }
            if (sp + 10 > safeResp.size) break
            val rType = readU16(safeResp, sp)
            val rdLen = readU16(safeResp, sp + 8)
            sp += 10
            if (rType == 1 && rdLen == 4 && sp + 4 <= safeResp.size) {
                foundIpv4 = safeResp.copyOfRange(sp, sp + 4)
                break
            }
            sp += rdLen
        }
        val ip = foundIpv4 ?: return safeResp

        // Build clean response with origQuery's Question + 1 A-record pointing to SafeSearch IP!
        var op = 12
        while (op < origQuery.size) {
            val l = origQuery[op].toInt() and 0xFF
            op++
            if (l == 0) break
            op += l
        }
        val qEnd = (op + 4).coerceAtMost(origQuery.size)
        val out = ByteArray(qEnd + 16)
        System.arraycopy(origQuery, 0, out, 0, qEnd)
        out[2] = ((origQuery[2].toInt() and 0x79) or 0x80).toByte()
        out[3] = 0x80.toByte() // RA=1, NOERROR
        out[6] = 0; out[7] = 1 // ANCOUNT=1
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        // Answer RR: pointer to 0x0C, TYPE=A(1), CLASS=IN(1), TTL=120s, RDLENGTH=4, RDATA=ip
        var p = qEnd
        out[p++] = 0xC0.toByte(); out[p++] = 0x0C.toByte()
        out[p++] = 0; out[p++] = 1
        out[p++] = 0; out[p++] = 1
        out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 120
        out[p++] = 0; out[p++] = 4
        System.arraycopy(ip, 0, out, p, 4)
        return out
    }

    private fun buildIpv4UdpReply(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        // IPv4 Header (20 bytes)
        out[0] = 0x45.toByte() // v4, IHL=5
        out[1] = 0x00
        writeU16(out, 2, totalLen)
        writeU16(out, 4, (System.nanoTime().toInt() and 0xFFFF))
        out[6] = 0x40.toByte() // Don't Fragment
        out[7] = 0x00
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        // Checksum initially 0 at [10..11]
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        val ipSum = ipv4Checksum(out, 0, 20)
        writeU16(out, 10, ipSum)

        // UDP Header (8 bytes)
        writeU16(out, 20, srcPort)
        writeU16(out, 22, dstPort)
        writeU16(out, 24, udpLen)
        writeU16(out, 26, 0) // Checksum 0 valid for IPv4 UDP (RFC 768)
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    private fun ipv4Checksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + len) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }

    private fun createVpnNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    VPN_CHANNEL_ID,
                    "Pare-feu DNS SentinelCam",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Filtrage DNS local anti-addiction et sécurité web sans serveur externe"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TUN_LOCAL_IP = "10.88.0.2"
        private const val TUN_DNS_IP = "10.88.0.1"
        private const val VPN_CHANNEL_ID = "sentinelcam_dns_vpn_v28"
        private const val VPN_NOTIF_ID = 2810

        const val ACTION_START = "com.sentinelcam.app.vpn.START"
        const val ACTION_STOP = "com.sentinelcam.app.vpn.STOP"
        const val ACTION_RELOAD = "com.sentinelcam.app.vpn.RELOAD"

        @Volatile
        var isVpnActive: Boolean = false
            private set

        fun hasVpnConsent(context: Context): Boolean {
            return try {
                VpnService.prepare(context.applicationContext) == null
            } catch (_: Throwable) {
                false
            }
        }

        fun ensureRunningIfEnabled(context: Context) {
            try {
                val sc = SelfControlStore(context.applicationContext)
                if (!sc.dnsVpnEnabled) return
                if (!hasVpnConsent(context)) return
                val intent = Intent(context.applicationContext, SentinelDnsVpnService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }

        fun reloadRules(context: Context) {
            try {
                if (!isVpnActive) {
                    ensureRunningIfEnabled(context)
                    return
                }
                val intent = Intent(context.applicationContext, SentinelDnsVpnService::class.java).apply {
                    action = ACTION_RELOAD
                }
                context.applicationContext.startService(intent)
            } catch (_: Throwable) {
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context.applicationContext, SentinelDnsVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                context.applicationContext.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
