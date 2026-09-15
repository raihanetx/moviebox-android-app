package com.moviebox.downloader.util

/**
 * SafeSearch content filter v3.2 — layered defense:
 *
 *   LAYER 1 — the site's own adult genre tags: "Adult" (Midnight catalog)
 *   and "Erotic" (the site's softcore tag — legit Hollywood erotic thrillers
 *   like Basic Instinct / Fifty Shades are NOT tagged Erotic on this site,
 *   so blocking the tag is safe).
 *
 *   LAYER 2 — a curated TITLE BLOCKLIST: known adult titles whose names
 *   contain no obvious keyword and that the site does NOT tag as adult
 *   (e.g. "Please Put Them On, Takamine-san"). Distinctive sub-phrases are
 *   matched as whole phrases.
 *
 *   LAYER 2b — HENTAI-OVA NAMING PATTERN: this site hosts a large catalog of
 *   untagged eroge-adaptation OVAs whose ONLY marker is the English suffix
 *   "The Animation" / "The Anime" (e.g. "Mesudachi The Animation"). Legit
 *   shows using the same pattern (Danganronpa / Persona franchises) are
 *   protected by a small whitelist. Applied to TITLES ONLY — never to
 *   descriptions, which legitimately say "the animation studio…".
 *
 *   LAYER 3 — keyword/phrase matching over title + description + genres,
 *   VARIATION-PROOF (v3):
 *     a) de-leet normalization on BOTH sides (p0rn→porn, s3x→sex,
 *        h3ntai→hentai, l0li→loli …)
 *     b) prefix stems — "pornx"/"pornstar"/"pornhub" still match "porn"
 *     c) fuzzy edit-distance — "hantaiy"/"hantai" land close to "hentai"
 *     d) explicit common misspellings (pron, henati, hentia, etchi …)
 *   v3.1 added the romanized Japanese adult vocabulary this catalog actually
 *   uses in untagged hentai titles (yariman, chikan, sukebe, sekuhara …),
 *   harvested live from the site, plus censored spellings ("f*cked").
 *   v3.2 adds the full crude sexual vocabulary (dick / fuck / ass / pussy /
 *   boobes / bum / snatch / cunt / … and misspellings, censored forms and
 *   adult-site names), word-form gaps found live-testing ("seduced" never
 *   matched "seduce"), and a fuzzy whitelist for innocent near-misses
 *   ("chennai" is distance 2 from "hentai" and was hiding the Indian
 *   "Beast"). Over-block accepted by design — SafeSearch is a toggle.
 *
 *   QUERY GATE — [isAdultQuery] blocks obviously adult-intent searches
 *   BEFORE any network call.
 *
 * SafeSearch is a toggle in the app; this file is the single place to tune
 * all layers. Preference: over-block rather than under-block.
 */
object ContentFilter {

    /* ---------- LAYER 1: the site's own adult genre tags ---------- */

    /** Genre values the site uses for its adult/softcore catalogs. */
    private val ADULT_GENRES = setOf("adult", "erotic", "pornographic", "pornography")

    fun hasAdultGenre(genres: List<String>): Boolean =
        genres.any { it.trim().lowercase() in ADULT_GENRES }

    fun hasAdultGenreCsv(csv: String): Boolean =
        hasAdultGenre(csv.split(',', ';'))

    /* ---------- LAYER 2: known adult titles (untagged by the site) ---------- */

    /** Distinctive sub-phrases of known adult titles. */
    private val BLOCKED_TITLES = setOf(
        // untagged borderline-hentai / ecchi anime reported by the user
        "yoasobi gurashi",        // Yoasobi Gurashi! (also Adult-tagged; belt & suspenders)
        "sweet agony",            // Adam's Sweet Agony
        "please put them on",     // Please Put Them On, Takamine-san
        "takamine san",           //   (same title, shorter form)
        "momoiro bouenkyou",      // Momoiro Bouenkyou Anime Edition (hentai OVA)
        "swamp stamp",            // SWAMP STAMP Anime Edition (hentai OVA)
        // more untagged hentai found while live-testing the above
        "garden the animation",   // Garden The Animation (hentai OVA)
        "takamine ke no nirinka", // Garden: Takamine-ke no Nirinka (hentai OVA)
        "no nie",                 // Mozu / Mouryou / Mashou no Nie hentai family
        "chuhai lips",            // Chuhai Lips (adult OVA)
        "fella hame",             // Fella Hame Lips (hentai OVA)
        "iribitari gal",          // Iribitari Gal ni Manko... (hentai)
        "manko tsukawasete",      //   (same title, second distinctive part)
        "makina san",             // Makina-san's a Love Bot?! (ecchi anime)
        "mankitsu",               // Ichijyoma Mankitsu Gurashi! (ecchi)
        // generic hentai-edition naming pattern used by this site
        "anime edition",
        // NTR (netorare) hentai naming pattern
        "netorare", "netorarete", "ntr",
        // untagged hentai series harvested from the site (v3.1)
        "shoujo kyouiku",        // Shoujo Kyouiku / RE (hentai)
        "sei shoujo",            // Sei Shoujo The Animation (hentai)
        "konbini shoujo",        // Konbini Shoujo Z (hentai)
        "shoujo sect",           // Shoujo Sect (hentai)
        "shoujo senki",          // Shoujo Senki Soul Eater / Brain Jacker
        "shoujo kara shoujo e",  // Shoujo kara Shoujo e… (hentai)
        "haitoku no shoujo",     // Haitoku no Shoujo (hentai)
        "mahou shoujo sae",      // Mahou Shoujo Sae (hentai)
        "seifuku shojo",         // Seifuku Shojo The Animation (hentai)
        "toumei ningen r",       // Toumei Ningen R (hentai series)
        "konna ni yasashiku sareta no", // Konna ni Yasashiku Sareta no
        "wicked lessons",        // Wicked Lessons (hentai)
        "guilty hole",           // Guilty Hole (adult animation)
        "do you like big girls", // Do You Like Big Girls? (ecchi/hentai)
        "junk land",             // Junk Land The Animation (hentai)
        "mizugi kanojo",         // Mizugi Kanojo The Animation (hentai)
        "heartful maman",        // Heartful Maman The Animation (hentai)
        "rennyuu order",         // Rennyuu Order The Animation (hentai)
        "succubus no shimobe",   // Boku wa Chiisana Succubus no Shimobe
        "succubus yondara",      // Succubus Yondara Haha Ga Kita!?
        "fushigi no kuni no succubus",
        "muma no machi",         // Muma no Machi Cornelica (hentai)
        "diabolus",              // Diabolus: Kikoku (hentai)
        "enyoku",                // Enyoku (hentai OVA)
        "enbi",                  // Enbi (hentai OVA)
        "enjo kouhai",           // Enjo Kouhai / Assisted Mating (hentai)
        "assisted mating",       //   (same title, English subtitle)
        "ingyaku",               // Soukou Seiki Ysphere / Reijou Caster (hentai)
        "frantic frustrated",    // F3: Frantic, Frustrated & Female (hentai)
        "taboo charming",        // Taboo Charming Mother (hentai)
        "yubiwa wo hazusu",      // Kyou wa Yubiwa wo Hazusu kara (hentai)
    )

    /* ---------- LAYER 2b: hentai-OVA suffix pattern (titles only) ---------- */

    /**
     * Eroge-adaptation OVA suffixes. "Batman: The Animated Series" and
     * "The Animatrix" do NOT contain these exact phrases (word-boundary
     * match), so they stay visible.
     */
    private val OVA_SUFFIX = setOf("the animation", "the anime")

    /** Legit franchises that legitimately use the OVA-style suffix. */
    private val TITLE_WHITELIST = setOf("danganronpa", "persona")

    /* ---------- LAYER 3: keyword / phrase list ---------- */

    private val PHRASES = setOf(
        // anime / hentai specific
        "hentai", "hentay", "hen tai", "ecchi", "ahegao", "futanari",
        "yuri", "yaoi", "shotacon", "lolicon", "loli", "shota", "jav",
        "doujin", "doujinshi", "eroge", "visual novel r18",
        // common misspellings / romanizations people actually type
        "pron",                   // also catches pr0n after de-leet
        "hantai", "henati", "hentia", "henti", "hetai", "entai", "etchi",
        // generic porn
        "porn", "porno", "xxx", "nsfw", "sex", "sexy", "sexual", "sexo",
        "erotic", "erotica", "sextape", "softcore", "hardcore", "r18", "r 18",
        // body / act terms
        "boobs", "tits", "titties", "nipple", "nipples", "nude", "nudes",
        "nudity", "naked", "topless", "milf", "blowjob", "handjob",
        "cumshot", "creampie", "anal", "orgy", "orgies", "threesome",
        "gangbang", "bukkake", "dildo", "lesbian", "panties", "panty",
        "upskirt", "voyeur", "striptease", "lewd", "fanservice", "fan service",
        // desire / affair terms (common in adult drama titles)
        "seduce", "seduction", "affair", "adultery", "cheating wife",
        "stepmom", "step mom", "stepmother", "stepsister", "step sister",
        "stepdaughter", "step daughter", "incest", "virgin",
        // regional / site-specific markers
        "blue film", "b grade", "bgrade", "uncensored", "censored", "uncut",
        "adults only", "x rated", "xrated", "18 sex", "adult movie",
        "gravure", "idol video",
        // romanized Japanese adult-genre vocabulary (common in hentai titles)
        "manko", "paizuri", "nakadashi", "shiofuki", "bakunyuu", "kyonyuu",
        "jukujo", "hitozuma", "saimin", "mesu kyoushi",
        // v3.1 — vocabulary harvested from this site's untagged hentai catalog
        "yariman", "enkou", "pakopako", "chikan", "sukebe", "sekuhara",
        "onaho", "junyuu", "yanmama", "jutai hen", "koubi", "sounyuu",
        "seiyoku", "kutsujoku", "daraku", "mesudachi", "kowaremono",
        "tsumamigui", "kemonokko", "yamitsuki", "oneshota", "h shiyo",
        "hasamazu", "meikoku", "rikujoubu", "kisaku", "shusaku", "letch",
        // sexual-assault terms (missed in v3 — "Please Rape Me!" passed)
        "rape", "raped", "raping", "rapist",
        // climax terms
        "orgasm",
        // censored spellings used to dodge filters ("F*cked" → "f cked"
        // after normalization collapses the asterisk into a space)
        "f cked", "f ked", "f cks", "f ks", "f cking", "f king", "fcked", "fcking",
        "f ck", "f k", "f uck", "f ucked", "f ucking", "f u c k",
        // fuck family NOT caught by the "fuck" prefix stem
        "motherfucker", "motherfuckers", "motherfucking", "mother fucker",
        "mother fucking", "clusterfuck",
        // dodge spellings of fuck
        "fuk", "fuked", "fuking", "fukked", "fukker", "phuck", "phuk",

        // v3.2 — crude anatomy & slang (user-requested full vocabulary)
        "ass", "asses", "asshole", "arse", "arsehole", "azz",
        "ass fuck", "assfuck", "assfucked", "assfucking",
        "butt fuck", "buttfuck", "buttfucked", "buttfucking",
        "bum", "bums", "booty", "tushy", "tushie", "butthole",
        "dick", "dicks", "dicked", "dicking", "dik", "dyck",
        "dickhead", "dickheads",
        "cock", "cocks", "deepthroat", "deep throat",
        "pussy", "pussies", "pusy", "pussi", "pushy", "coochie", "cooch",
        "twat", "twats", "cunt", "cunts", "snatch", "vajayjay",
        "boob", "boobes", "boobies", "tities",
        "penis", "penises", "penus", "vagina", "vaginas", "vaginal",
        "clit", "clitoris", "labia", "testicles", "testicle", "scrotum",
        "anus", "rectum",
        "cum", "cums", "cumming", "cumshots", "jizz", "jizzed",
        "squirt", "squirting",

        // v3.2 — acts & positions
        "tit fuck", "titfuck", "titfucking", "titjob",
        "footjob", "blow job", "blowjobs", "handjobs", "boobjob",
        "gloryhole", "glory hole", "circle jerk", "circlejerk",
        "teabagging", "tea bagging", "rimming", "rimjob",
        "fellatio", "cunnilingus", "gokkun",
        "doggy style", "doggiestyle", "reverse cowgirl",
        "sixty nine", "sixtynine", "69",
        "facesitting", "face sitting", "pegging", "foursome", "blowbang",
        "ass to mouth", "balls deep",
        "masturbate", "masturbating", "masturbation",
        "jerk off", "jerkoff", "jerking off", "jacking off",
        "wank", "wanking", "wanker", "fap", "fapping",
        "orgasms", "moan", "moaning",

        // v3.2 — crude descriptors & profanity
        "horny", "thot", "thots", "slut", "sluts", "slutty",
        "whore", "whores", "whoring", "bitch", "bitches",
        "bastard", "bastards",
        "nympho", "nymphomaniac", "nymphomania",
        "cuckold", "cuck", "hotwife", "swinger", "swingers",

        // v3.2 — adult categories / industry terms
        "shemale", "ladyboy", "tranny", "futa",
        "bbw", "ssbbw", "milfs", "dilf", "gilf",
        "camgirl", "cam girl", "camgirls", "camwhore",
        "onlyfans", "only fans", "stripper", "strippers", "strip tease",
        "lap dance", "lapdance",
        "nudist", "nudists", "nudism", "downblouse", "down blouse",
        "undress", "undressed",
        "prostitute", "prostitutes", "prostitution",
        "hooker", "hookers", "call girl", "call girls", "brothel", "whorehouse",
        "erotic massage", "nuru", "nuru massage",
        "fetish", "bdsm", "bondage", "dominatrix", "femdom", "submissive",
        "sadomasochism", "vibrator", "vibrators", "dildos",
        "buttplug", "butt plug", "kinky", "kink", "kinks", "shibari",
        "lingerie", "barely legal", "barely 18",

        // v3.2 — assault / groping
        "groped", "groping", "molest", "molested", "molestation", "molester",
        "fondle", "fondled", "fondling",

        // v3.2 — anime/hentai adult vocabulary
        "yiff", "oppai", "pantsu", "panchira", "ryona", "guro", "vore",
        "femboy", "otokonoko", "succubus", "incubus", "kunoichi",
        "shokushu", "ryoujoku", "choukyou", "sumata", "omanko", "ochinpo",
        "chinko", "bonyuu",

        // v3.2 — word-form gaps found while live-testing
        "seduced", "seduces", "seducing", "seductive", "seductress",
        "seducer", "seducers",
        "rapes", "rapists", "virgins", "virginity",
        "lesbians", "lesbo",
        "stepbrother", "step brother", "stepdad", "step dad",
        "stepsis", "step sis", "stepson", "step son",
        "stepcousin", "step cousin",

        // v3.2 — adult site names (typed straight into search)
        "xnxx", "xvideos", "xvideo", "xhamster", "spankbang", "redtube",
        "tube8", "eporner", "hqporner", "beeg", "brazzers", "bangbros",
        "naughty america", "teamskeet", "youjizz",
    )

    /**
     * Prefix stems: any word STARTING with these counts as a match, so
     * "pornx" / "pornstar" / "pornhub" hit "porn", "fucked" / "fucking" /
     * "fuckboy" hit "fuck". Deliberately tiny — stems over-block, so only
     * unambiguous roots belong here (NOT "anal"/"dick"/"cock"/"ass", which
     * would catch "analysis"/"Dickens"/"cockpit"/"assassin").
     */
    private val STEMS = listOf("porn", "sex", "hentai", "hentay", "ecchi", "fck", "fuck")

    /**
     * Fuzzy roots: a word within edit distance 1 (>= 5 letters) or 2
     * (>= 7 letters) of one of these counts as a match — catches
     * "hantaiy", "hantai", "echhi". "porn" is excluded on purpose
     * (distance-1 words like "ports" would over-block); its stem + "pron"
     * already cover the realistic variants.
     */
    private val FUZZY_ROOTS = listOf(
        "hentai", "ecchi", "ahegao", "futanari", "lolicon", "shotacon", "doujin",
    )

    /**
     * Innocent words inside the fuzzy window that must NOT count as a hit.
     * "chennai" is distance 2 from "hentai" and appears in many clean
     * South-Indian movie synopses — without this the Indian "Beast" (2022)
     * was hidden.
     */
    private val FUZZY_WHITELIST = setOf("chennai")

    /* ---------- normalization: lowercase → de-leet → collapse ---------- */

    private fun deLeet(c: Char): Char = when (c) {
        '0' -> 'o'
        '1' -> 'i'
        '3' -> 'e'
        '4' -> 'a'
        '5' -> 's'
        '7' -> 't'
        '@' -> 'a'
        '$' -> 's'
        '!' -> 'i'
        else -> c
    }

    /** lowercase, de-leet, every non [a-z0-9] run -> one space, trimmed. */
    private fun norm(s: String): String =
        s.lowercase().map { deLeet(it) }.joinToString("")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Needles go through the SAME pipeline as the text (keeps r18 etc. consistent). */
    private val BLOCKED_N: Set<String> = BLOCKED_TITLES.map { norm(it) }.toSet()
    private val PHRASES_N: Set<String> = PHRASES.map { norm(it) }.toSet()
    private val STEMS_N: List<String> = STEMS.map { norm(it) }
    private val OVA_SUFFIX_N: Set<String> = OVA_SUFFIX.map { norm(it) }.toSet()
    private val TITLE_WHITELIST_N: Set<String> = TITLE_WHITELIST.map { norm(it) }.toSet()

    /* ---------- matching ---------- */

    /** Whole word/phrase containment on normalized text. */
    private fun matches(haystack: String, needles: Set<String>): Boolean {
        if (haystack.isEmpty()) return false
        val padded = " $haystack "
        return needles.any { padded.contains(" $it ") }
    }

    /** Any whole word starting with one of the stems? */
    private fun hasStemWord(haystack: String): Boolean =
        haystack.split(' ').any { w -> STEMS_N.any { w.startsWith(it) } }

    private fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[n]
    }

    /** Any word a near-miss (typo) of a fuzzy root? */
    private fun fuzzyMatch(haystack: String): Boolean =
        haystack.split(' ').any { w ->
            w.length >= 5 && w !in FUZZY_WHITELIST && FUZZY_ROOTS.any { r ->
                w.length >= r.length - 1 &&
                    editDistance(w, r) <= if (w.length >= 7) 2 else 1
            }
        }

    /** LAYER 3 in full: keywords + stems + fuzzy on a normalized text. */
    private fun hasAdultTextSignal(text: String): Boolean =
        matches(text, PHRASES_N) || hasStemWord(text) || fuzzyMatch(text)

    /**
     * LAYER 2b — hentai-OVA suffix pattern, TITLES ONLY (never descriptions:
     * a legit synopsis can say "the animation was produced by…").
     * Whitelisted franchises (Danganronpa, Persona) skip only this check;
     * all other checks still run against them.
     */
    private fun hasOvaSuffix(title: String): Boolean {
        // substring match on purpose: "persona4"/"persona5" are single words,
        // and a whitelist hit only skips THIS check — every other layer still runs.
        if (TITLE_WHITELIST_N.any { title.contains(it) }) return false
        return matches(title, OVA_SUFFIX_N)
    }

    /** LAYER 2 + 2b + LAYER 3 — the "is this name adult?" check. */
    private fun isAdultTitle(title: String): Boolean =
        matches(title, BLOCKED_N) || hasOvaSuffix(title) || hasAdultTextSignal(title)

    /* ---------- public API ---------- */

    /**
     * QUERY GATE — is the search itself adult-intent? Blocks the search
     * before any network call. Same signals as a title check.
     */
    fun isAdultQuery(q: String): Boolean = isAdultTitle(norm(q))

    /**
     * Search-result level check (L1 + L2 + L3 on the title).
     * Genres come from the site's "genre" CSV on each search item.
     */
    fun isAdultResult(title: String, genres: List<String>): Boolean =
        hasAdultGenre(genres) || isAdultTitle(norm(title))

    /** Detail-level check: L1 + L2 + 2b-on-title + L3 over title, description and genres. */
    fun isAdultDetail(title: String, description: String, genres: List<String>): Boolean =
        hasAdultGenre(genres) ||
            matches(norm(title), BLOCKED_N) ||
            hasOvaSuffix(norm(title)) ||
            hasAdultTextSignal(norm(listOf(title, description).joinToString(" "))) ||
            hasAdultTextSignal(norm(genres.joinToString(" ")))

    /* ---------- wall-2 support: soft suggestive markers ---------- */

    /**
     * Romance-drama vocabulary that is perfectly normal entertainment
     * content on its own ("Midnight Desire", "Passionate Love") and must
     * NEVER block anything by itself. NsfwImageClassifier combines it with
     * a sexy-leaning poster score (>= 0.55) to catch borderline softcore
     * that slips past the hard blocklist — the "combined vote" rule.
     */
    private val SOFT_MARKERS = setOf(
        "desire", "desires", "passion", "passionate", "tempt", "temptation",
        "tempted", "forbidden", "obsession", "obsessed", "lust", "lustful",
        "sensual", "sultry", "steamy", "cheating", "one night", "onenight",
        "booty call", "friends with benefits", "no strings", "playboy",
        "midnight", "after hours", "secret love", "secret desire",
    )

    private val SOFT_MARKERS_N: Set<String> = SOFT_MARKERS.map { norm(it) }.toSet()

    /**
     * Title-only soft signal for the combined-vote rule. A hit here alone
     * changes nothing; it only matters when the poster image also leans
     * "sexy". Checked against the RAW title (whole-phrase, normalized).
     */
    fun hasSoftSignal(title: String): Boolean =
        if (title.isEmpty()) false else matches(norm(title), SOFT_MARKERS_N)
}
