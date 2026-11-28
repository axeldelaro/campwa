package com.sentinelcam.app.vpn

import java.util.Locale

/**
 * Built-in Zero-Server DNS Category Catalog, App↔Web Coupling Matrix,
 * DoH Anti-Bypass Domains, and SafeSearch Enforcement Targets (SentinelCam v28.0).
 */
object DnsBlocklistCatalog {

    /** DNS-over-HTTPS endpoints blocked while local DNS firewall is active to prevent browser bypass. */
    val DOH_BYPASS_DOMAINS: Set<String> = setOf(
        "dns.google",
        "dns.google.com",
        "dns64.dns.google",
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "one.one.one.one",
        "dns.quad9.net",
        "dns9.quad9.net",
        "doh.opendns.com",
        "dns.nextdns.io",
        "doh.dns.sb",
        "dns.adguard.com",
        "dns.adguard-dns.com",
        "dns.mullvad.net",
        "doh.mullvad.net",
        "dns.controld.com"
    )

    val CATEGORY_DOMAINS: Map<String, Set<String>> = mapOf(
        "adult" to setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
            "youporn.com", "tube8.com", "spankbang.com", "eporner.com", "hqporner.com",
            "txxx.com", "beeg.com", "chaturbate.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "cam4.com", "myfreecams.com", "onlyfans.com", "fansly.com",
            "mym.fans", "brazzers.com", "naughtyamerica.com", "bangbros.com", "realitykings.com",
            "rule34.xxx", "nhentai.net", "hanime.tv", "hentaihaven.xxx", "e-hentai.org",
            "literotica.com", "sex.com", "porn.com", "tnaflix.com", "drtuber.com",
            "sunporno.com", "motherless.com", "fapello.com", "coomer.su", "kemono.su",
            "jacquieetmichel.net", "jacquieetmicheltv.net", "tukif.com", "dorcelclub.com",
            "erome.com", "redgifs.com", "porntrex.com", "daftsex.com", "ixxx.com",
            "xvideos-cdn.com", "phncdn.com", "xhcdn.com"
        ),
        "social" to setOf(
            "tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly", "bytedance.com",
            "instagram.com", "cdninstagram.com", "threads.net",
            "facebook.com", "fb.com", "fbcdn.net", "m.facebook.com",
            "snapchat.com", "sc-cdn.net",
            "twitter.com", "x.com", "twimg.com", "t.co",
            "reddit.com", "redd.it", "redditstatic.com", "old.reddit.com",
            "pinterest.com", "pinimg.com",
            "tumblr.com", "9gag.com", "4chan.org", "4cdn.org",
            "bsky.app", "mastodon.social", "discord.com", "discord.gg"
        ),
        "streaming" to setOf(
            "youtube.com", "youtu.be", "m.youtube.com", "ytimg.com", "googlevideo.com",
            "twitch.tv", "ttvnw.net", "kick.com",
            "netflix.com", "nflxvideo.net", "disneyplus.com", "primevideo.com",
            "hulu.com", "max.com", "hbomax.com", "crunchyroll.com", "adn.fr",
            "dailymotion.com", "vimeo.com", "rumble.com", "odysee.com",
            "molotov.tv", "mycanal.fr", "canalplus.com", "tf1.fr", "6play.fr"
        ),
        "gaming" to setOf(
            "roblox.com", "rbxcdn.com", "steampowered.com", "steamcommunity.com",
            "epicgames.com", "fortnite.com", "riotgames.com", "leagueoflegends.com",
            "valorant.com", "ea.com", "ubisoft.com", "blizzard.com", "battle.net",
            "minecraft.net", "supercell.com", "chess.com", "lichess.org",
            "poki.com", "crazygames.com", "miniclip.com", "kongregate.com",
            "friv.com", "y8.com", "coolmathgames.com", "itch.io", "gog.com",
            "xbox.com", "playstation.com", "nintendo.com", "geforcenow.com"
        ),
        "gambling_crypto" to setOf(
            "betclic.fr", "betclic.com", "winamax.fr", "winamax.com", "unibet.fr", "unibet.com",
            "parionssport.fdj.fr", "fdj.fr", "pmu.fr", "zebet.fr", "bwin.fr", "bwin.com",
            "bet365.com", "stake.com", "stake.bet", "pokerstars.fr", "pokerstars.com",
            "1xbet.com", "draftkings.com", "fanduel.com", "polymarket.com",
            "binance.com", "coinbase.com", "kraken.com", "bybit.com", "okx.com",
            "kucoin.com", "bitget.com", "crypto.com", "etoro.com", "robinhood.com",
            "tradingview.com", "dexscreener.com", "pump.fun"
        ),
        "news" to setOf(
            "bfmtv.com", "cnews.fr", "lemonde.fr", "lefigaro.fr", "leparisien.fr",
            "20minutes.fr", "francetvinfo.fr", "liberation.fr", "lci.fr", "tf1info.fr",
            "huffingtonpost.fr", "konbini.com", "brut.media", "jeuxvideo.com",
            "cnn.com", "foxnews.com", "bbc.com", "nytimes.com", "theguardian.com",
            "dailymail.co.uk", "buzzfeed.com", "news.google.com", "msn.com"
        ),
        "ai_unfiltered" to setOf(
            "character.ai", "beta.character.ai", "janitorai.com", "chub.ai",
            "crushon.ai", "spicychat.ai", "candy.ai", "replika.com", "replika.ai",
            "chai-research.com", "chai.ml", "poe.com", "civitai.com",
            "perchance.org", "venice.ai", "novelai.net", "talkie-ai.com"
        ),
        "apk_proxy" to setOf(
            "apkmirror.com", "apkpure.com", "apkpure.net", "uptodown.com",
            "aptoide.com", "happymod.com", "moddroid.com", "an1.com", "apkcombo.com",
            "f-droid.org", "proxysite.com", "croxyproxy.com", "hide.me",
            "kproxy.com", "whoer.net", "blockaway.net", "plainproxies.com",
            "torproject.org", "nordvpn.com", "expressvpn.com", "protonvpn.com",
            "surfshark.com", "windscribe.com", "mullvad.net"
        )
    )

    /** Adult domain token heuristic (only active when `adult` category is enabled). */
    val ADULT_DOMAIN_TOKENS: List<String> = listOf(
        "porn", "xxx", "xnxx", "xvideos", "xhamster", "hentai", "rule34",
        "chaturbate", "stripchat", "camgirl", "onlyfans", "spankbang", "redtube", "youporn", "nsfw"
    )

    /**
     * Absolute App ↔ Web Coupling:
     * When a package is blocked, quota-exhausted, or paused, its web equivalents are automatically
     * blocked in EVERY browser and in-app WebView!
     */
    val PACKAGE_TO_DOMAINS: Map<String, Set<String>> = mapOf(
        "com.zhiliaoapp.musically" to setOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly"),
        "com.ss.android.ugc.trill" to setOf("tiktok.com", "tiktokcdn.com", "tiktokv.com"),
        "com.instagram.android" to setOf("instagram.com", "cdninstagram.com", "threads.net"),
        "com.google.android.youtube" to setOf("youtube.com", "youtu.be", "m.youtube.com", "googlevideo.com"),
        "com.twitter.android" to setOf("x.com", "twitter.com", "twimg.com"),
        "com.snapchat.android" to setOf("snapchat.com", "sc-cdn.net"),
        "com.facebook.katana" to setOf("facebook.com", "fb.com", "m.facebook.com", "fbcdn.net"),
        "com.reddit.frontpage" to setOf("reddit.com", "redd.it", "old.reddit.com"),
        "com.discord" to setOf("discord.com", "discord.gg"),
        "tv.twitch.android.app" to setOf("twitch.tv", "ttvnw.net"),
        "com.netflix.mediaclient" to setOf("netflix.com", "nflxvideo.net"),
        "com.pinterest" to setOf("pinterest.com", "pinimg.com"),
        "com.roblox.client" to setOf("roblox.com", "rbxcdn.com")
    )

    /** SafeSearch upstream CNAME rewrite targets. */
    val SAFESEARCH_REWRITES: Map<String, String> = mapOf(
        "www.google.com" to "forcesafesearch.google.com",
        "google.com" to "forcesafesearch.google.com",
        "www.google.fr" to "forcesafesearch.google.com",
        "google.fr" to "forcesafesearch.google.com",
        "www.google.be" to "forcesafesearch.google.com",
        "www.google.ch" to "forcesafesearch.google.com",
        "www.google.ca" to "forcesafesearch.google.com",
        "www.youtube.com" to "restrict.youtube.com",
        "m.youtube.com" to "restrict.youtube.com",
        "youtubei.googleapis.com" to "restrict.youtube.com",
        "www.bing.com" to "strict.bing.com",
        "bing.com" to "strict.bing.com",
        "duckduckgo.com" to "safe.duckduckgo.com",
        "www.duckduckgo.com" to "safe.duckduckgo.com"
    )

    /** Checks if `host` equals or is a subdomain of any domain in `domainSet`. */
    fun matchesDomainSet(hostLower: String, domainSet: Set<String>): Boolean {
        if (hostLower.isEmpty() || domainSet.isEmpty()) return false
        var cur = hostLower
        while (true) {
            if (cur in domainSet) return true
            val dot = cur.indexOf('.')
            if (dot <= 0 || dot >= cur.length - 1) return false
            cur = cur.substring(dot + 1)
        }
    }

    fun normalizeHost(raw: String): String =
        raw.trim().lowercase(Locale.US).trimEnd('.')
}
