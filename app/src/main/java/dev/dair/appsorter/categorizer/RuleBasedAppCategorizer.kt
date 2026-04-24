package dev.dair.appsorter.categorizer

import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp

class RuleBasedAppCategorizer : AppCategorizer {

    // 先頭のルールから順に .contains() でマッチ。最初に当たったカテゴリを採用。
    // 特定度の高いキーワードを上に、汎用的なキーワードを下に置く。
    private val rules = linkedMapOf(
        "Crypto" to listOf(
            "coincheck", "bitflyer", "bitbank", "binance", "gmo coin", "kraken",
            "crypto", "bitcoin", "ethereum", "仮想通貨", "暗号資産"
        ),
        "Points" to listOf(
            "ポイント", "point card", "dポイント", "tポイント", "tカード",
            "ponta", "楽天ポイント", "rakuten point",
            "マツキヨ", "ツルハ", "welcia", "ウエルシア", "スギ薬局", "ココカラ",
            "セブン", "7-eleven", "seven-eleven", "ローソン", "ファミマ", "familymart", "family mart",
            "イオンウォレット", "イオン ", "aeon", "yodobashi", "ヨドバシ",
            "bic camera", "ビックカメラ", "ヤマダ", "yamada",
            "ホットペッパー", "hot pepper", "epark", "gyomu", "業務スーパー",
            "ニトリ", "nitori", "クーポン", "coupon", "スタンプ", "stamp rally"
        ),
        "Payment" to listOf(
            "paypay", "ペイペイ", "d払い", "d pay", "au pay", "aupay", "auペイ",
            "merpay", "メルペイ", "楽天ペイ", "rakuten pay", "ゆうちょペイ",
            "suica", "pasmo", "icoca", "waon", "nanaco", "edy",
            "quicpay", "id決済", " iD ", "電子マネー", "ウォレット", "wallet ",
            ".wallet", "googlepay", "google pay", "apple pay", "applepay"
        ),
        "Banking" to listOf(
            "bank", "銀行", "信金", "信用金庫", "credit union", "shinkin",
            "証券", "securities", "sbi", "野村", "大和", "松井", "楽天証券",
            "マネフォ", "money forward", "moneyforward", "zaim", "家計簿",
            "moneytree", "wise", "revolut", "paypal"
        ),
        "Mobility" to listOf(
            "go taxi", " go ", "didi", "uber", "s.ride", "taxi", "タクシー",
            "car share", "カーシェア", "タイムズ", "times car", "ev charge",
            "充電", "ガソリン", "enechan", "エネチェンジ", "dカーシェア",
            "parking", "駐車場"
        ),
        "Navigation" to listOf(
            "maps", "map", "地図", "ナビ", "navigation", "navitime", "ナビタイム",
            "乗換", "乗換案内", "transit", "駅すぱあと", "jr東日本", "jr west",
            "jre", "jorudan", "ジョルダン", "yahoo!乗換", "yahoo! map"
        ),
        "Travel" to listOf(
            "booking", "agoda", "expedia", "hotels", "airbnb",
            "じゃらん", "jalan", "skyscanner", "trivago", "一休", "ikyu",
            "楽天トラベル", "rakuten travel", "jtb", "hisホリデー",
            "ana", "jal", "peach", "jetstar", "airline", "airport", "airways"
        ),
        "Food" to listOf(
            "uber eats", "demaekan", "出前館", "wolt", "menu",
            "tabelog", "食べログ", "gurunavi", "ぐるなび",
            "cookpad", "クックパッド", "delish", "kurashiru", "クラシル",
            "recipe", "レシピ", "restaurant", "外食"
        ),
        "Communication" to listOf(
            "line app", " line ", "line:", "slack", "discord", "teams", "zoom",
            "whatsapp", "messenger", "telegram", "wechat", "skype",
            "chatwork", "チャットワーク", "hangout", "google meet", "webex"
        ),
        "SNS" to listOf(
            "twitter", "x app", "instagram", "threads", "facebook",
            "tiktok", "pinterest", "mastodon", "misskey", "bluesky", "bereal",
            "snapchat", "linkedin", "reddit", "tumblr", "mixi"
        ),
        "Mail" to listOf(
            "gmail", "outlook", "yahoo!メール", "yahoo mail", "ymail",
            "protonmail", "proton mail", "spark mail", "thunderbird",
            "email", "メール", "mailer"
        ),
        "Music" to listOf(
            "spotify", "apple music", "amazon music", "youtube music",
            "radiko", "radio", "podcast", "soundcloud", "sound cloud",
            "awa", "line music", "deezer", "tidal"
        ),
        "Video" to listOf(
            "youtube", "netflix", "prime video", "disney", "abema", "abematv",
            "hulu", "niconico", "ニコニコ", "tver", "dazn", "paravi",
            "unext", "u-next", "lemino", "fod", "wowow",
            "video", "動画", "stream"
        ),
        "Photo" to listOf(
            "camera", "カメラ", "gallery", "ギャラリー", "photos", "写真",
            "snapseed", "lightroom", "picsart", "vsco", "canva", "snow",
            "beauty cam", "美顔", "b612", "foodie", "prisma"
        ),
        "Game" to listOf(
            "game", "ゲーム", "puzzle", "quest", "clash", "dragon", "rpg",
            "monster", "genshin", "原神", "roblox", "pokemon", "ポケモン",
            "play games", "ぷよ", "どうぶつの森", "minecraft", "fortnite",
            "pubg", "モンスト", "ウマ娘", "fgo", "スクスタ"
        ),
        "Shopping" to listOf(
            "amazon shopping", "amazon ", "楽天市場", "rakuten ", "yahoo!ショッピング",
            "mercari", "メルカリ", "paypayフリマ", "shein", "temu",
            "zozotown", "zozo", "uniqlo", "ユニクロ", "gu ", "zara", "h&m",
            "shopping", "shop", "store", "ec"
        ),
        "Health" to listOf(
            "health", "ヘルス", "fit", "fitness", "walk", "歩数",
            "sleep", "睡眠", "medical", "医療", "clinic", "クリニック",
            "pharmacy", "drugstore", "薬局", "dental", "歯医者",
            "ヨガ", "yoga", "nike training", "asken"
        ),
        "Education" to listOf(
            "duolingo", "study sapuri", "スタディサプリ", "anki",
            "kindle", "book", "本", "読書", "ebook", "manga", "マンガ", "漫画",
            "dictionary", "辞書", "weblio", "english", "英語",
            "lesson", "レッスン", "learn", "コース"
        ),
        "News" to listOf(
            "news", "ニュース", "yahoo!ニュース", "nhk", "smartnews", "スマニュ",
            "日経", "nikkei", "cnn", "bbc", "times",
            "weather", "天気", "tenki", "forecast", "気象"
        ),
        "Work" to listOf(
            "notion", "google drive", "docs", "sheet", "slides", "office",
            "onedrive", "dropbox", "figma", "miro", "todoist", "asana",
            "trello", "monday", "github", "gitlab", "jira", "confluence",
            "keep", "todo", "タスク", "evernote"
        ),
        "Utility" to listOf(
            "calculator", "電卓", "計算機", "qr", "バーコード", "scanner",
            "translate", "翻訳", "password", "1password", "bitwarden",
            "files", "ファイル", "zip", "compress", "wifi analyzer",
            "ピンコード", "voice recorder", "録音"
        ),
        "System" to listOf(
            "settings", "設定", "launcher", "ランチャー", "widget", "ウィジェット",
            "galaxy store", "galaxy wearable", "xiaomi", "one ui", "miui",
            "my docomo", "my au", "my softbank", "端末管理", "device care"
        )
    )

    override suspend fun categorize(batch: List<InstalledApp>): List<CategorizedApp> {
        return batch.map { app ->
            CategorizedApp(
                appName = app.appName,
                packageName = app.packageName,
                category = detectCategory(app),
                source = "offline-rules"
            )
        }
    }

    private fun detectCategory(app: InstalledApp): String {
        val haystack = "${app.appName} ${app.packageName}".lowercase()
        for ((category, keywords) in rules) {
            if (keywords.any { haystack.contains(it.lowercase()) }) {
                return category
            }
        }
        return "Other"
    }
}
