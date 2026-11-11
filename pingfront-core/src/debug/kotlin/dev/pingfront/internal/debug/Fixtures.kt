// Preview copy in every language: each message stays on one line so it reads and diffs as one string.
@file:Suppress("ktlint:standard:max-line-length")

package dev.pingfront.internal.debug

import dev.pingfront.InternalPingFrontApi
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.ConversationPhase
import dev.pingfront.conversation.ConversationState
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.model.Author
import dev.pingfront.model.Card
import dev.pingfront.model.CardResult
import dev.pingfront.model.ChatLook
import dev.pingfront.model.ChoiceOption
import dev.pingfront.model.ColorScheme
import dev.pingfront.model.Corners
import dev.pingfront.model.Density
import dev.pingfront.model.FontStyle
import dev.pingfront.model.HeaderStyle
import dev.pingfront.model.LocalizedText
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.PingOrigin
import dev.pingfront.model.PingReason
import dev.pingfront.model.ReceiptAction
import dev.pingfront.model.ReviewMode
import dev.pingfront.model.TeamMember
import dev.pingfront.model.Timestamps
import dev.pingfront.model.TopicSymbol
import java.time.Instant

/**
 * Offline fixture data for the preview launch extra, Compose previews and snapshot tests.
 * Team content is written as each team would write it (the SDK does not translate it).
 */
@InternalPingFrontApi
public object Fixtures {
    // Builders for tests in other modules: the SDK produces these types, so their constructors are internal (review AN-23).

    public fun storeOffer(
        kind: dev.pingfront.model.StoreOffer.Kind,
        id: String,
        productId: String? = null,
        basePlanId: String? = null,
        offerId: String? = null,
    ): dev.pingfront.model.StoreOffer = dev.pingfront.model.StoreOffer(kind, id, productId, basePlanId, offerId)

    public fun offerRequest(
        offerId: String,
        slot: OfferSlot?,
        cardId: String,
        messageId: String,
        storeOffer: dev.pingfront.model.StoreOffer? = null,
    ): dev.pingfront.OfferRequest = dev.pingfront.OfferRequest(offerId, slot, cardId, messageId, storeOffer)

    public fun receipt(action: ReceiptAction, text: String): MessagePart.Receipt = MessagePart.Receipt(action, text)

    public fun closed(reason: dev.pingfront.model.SendPolicy.Reason): dev.pingfront.model.SendPolicy.Closed = dev.pingfront.model.SendPolicy.Closed(reason)

    public fun badKey(problem: dev.pingfront.KeyProblem, message: String): dev.pingfront.ConfigurationProblem =
        dev.pingfront.ConfigurationProblem(dev.pingfront.ConfigurationProblem.Kind.InvalidKey(problem), message, true)

    public fun teamMember(member: TeamMember, name: String = member.name, avatarUrl: String? = member.avatarUrl): TeamMember =
        member.copy(name = name, avatarUrl = avatarUrl)

    /** Which screen a fixture shows. */
    public enum class Kind { Welcome, Conversation }

    /** 30 Oct 2026, 18:00 UTC. */
    public val Base: Instant = Instant.ofEpochSecond(1_793_390_400)

    public fun at(minutes: Double): Instant = Base.plusMillis((minutes * 60_000).toLong())

    /** One example app: name, team, copy and look. */
    public class App(
        public val id: String,
        public val name: String,
        public val members: List<TeamMember>,
        public val copy: Copy,
        public val offer: OfferSlot,
        public val photoKey: String,
        public val look: ChatLook,
        /** The person writing in, with an illustrated CC0 portrait (brand/avatars, Notionists). */
        public val person: Author = Author(Author.Kind.Person, "p_riley", "Riley", "fixture://avatar/person-riley"),
    )

    /** Team-written content in one language. */
    public class Copy(
        public val role: String,
        public val explanation: String,
        public val ping: String,
        public val question: String,
        public val ai: String,
        public val extended: String,
        public val receipt: String,
        public val offerTitle: String,
        public val offerBody: String,
        public val offerButton: String,
        public val useFor: List<String>,
        public val photo: String,
        public val askReport: String,
        public val reportTitle: String,
        public val reportButton: String,
        public val linkTitle: String,
        public val linkButton: String,
        public val timerQuestion: String,
        public val timers: List<String>,
        public val ratingQuestion: String,
        public val reviewText: String,
        public val reviewButton: String,
        public val replyExpectation: String = "Usually replies in a few hours",
    )

    private val deniz = TeamMember("m_deniz", "Deniz", "Maker of Clockwise", "fixture://avatar/deniz", "Europe/Istanbul")
    private val mara = TeamMember("m_mara", "Mara", "Support", "fixture://avatar/mara", "Europe/Berlin")

    // Portraits come from the shared CC0 set in brand/avatars: Open Peeps for the team, Notionists line drawings for
    // the people writing in (Riley, Sam and Jo).
    private val ana = TeamMember("m_ana", "Ana", "Makes Ledgerly", "fixture://avatar/ana", "Europe/Lisbon")
    private val tom = TeamMember("m_tom", "Tom", "Support at Ledgerly", "fixture://avatar/tom", "Europe/Berlin")
    private val kofi = TeamMember("m_kofi", "Kofi", "Makes Driftwood", "fixture://avatar/kofi", "Europe/Amsterdam")
    private val lena = TeamMember("m_lena", "Lena", "Support at Driftwood", "fixture://avatar/lena", "Europe/Berlin")

    public val English: Copy = Copy(
        role = "Maker of Clockwise", explanation = "Sent automatically after you closed Plans twice",
        ping = "Hi Riley, I'm Deniz. I make Clockwise. You closed the plans screen twice today. Anything unclear about Pro?",
        question = "Is the yearly plan refundable if I change my mind?",
        ai = "Yes. Google Play refunds subscriptions bought in the last 48 hours. Open Play Store, tap your profile, then Payments & subscriptions.",
        extended = "Adding to that: I extended your trial so you can try widgets first.", receipt = "Trial extended to 14 Nov",
        offerTitle = "Pro yearly, first month free", offerBody = "Widgets, unlimited alarms and sync across your devices.", offerButton = "See the offer",
        useFor = listOf("Work focus", "Workouts", "Cooking"),
        photo = "Here is the widget glitch I mentioned.",
        askReport = "Thanks, I can see it. Could you send a report from the widget screen? It includes the logs I need.",
        reportTitle = "Report the widget glitch", reportButton = "Report a bug", linkTitle = "How widgets refresh", linkButton = "Read the guide",
        timerQuestion = "Which alarm do you set most?", timers = listOf("Weekdays", "Naps", "Workouts"),
        ratingQuestion = "How was this conversation?", reviewText = "If Clockwise helps your day, a review helps us a lot.", reviewButton = "Rate Clockwise",
    )

    public val Copies: Map<String, Copy> = mapOf(
        "en" to English,
        "de" to Copy(
            role = "Entwickler von Clockwise", explanation = "Automatisch gesendet, nachdem du die Tarife zweimal geschlossen hast",
            ping = "Hallo Riley, ich bin Deniz und entwickle Clockwise. Du hast die Tarifübersicht heute zweimal geschlossen. Ist bei Pro etwas unklar?",
            question = "Kann ich das Jahresabo zurückgeben, wenn ich es mir anders überlege?",
            ai = "Ja. Google Play erstattet Abos, die in den letzten 48 Stunden gekauft wurden. Öffne den Play Store, tippe auf dein Profil und dann auf Zahlungen und Abos.",
            extended = "Noch dazu: Ich habe deinen Testzeitraum verlängert, damit du zuerst die Widgets ausprobieren kannst.", receipt = "Testzeitraum bis 14. Nov. verlängert",
            offerTitle = "Pro jährlich, erster Monat kostenlos", offerBody = "Widgets, unbegrenzte Wecker und Synchronisierung auf allen Geräten.", offerButton = "Angebot ansehen",
            useFor = listOf("Konzentriertes Arbeiten", "Training", "Kochen"),
            photo = "Hier ist der Widget-Fehler, von dem ich erzählt habe.",
            askReport = "Danke, ich sehe ihn. Kannst du vom Widget-Bildschirm aus einen Bericht senden? Er enthält die Logs, die ich brauche.",
            reportTitle = "Widget-Fehler melden", reportButton = "Fehler melden", linkTitle = "Wie sich Widgets aktualisieren", linkButton = "Anleitung lesen",
            timerQuestion = "Welchen Wecker stellst du am häufigsten?", timers = listOf("Werktags", "Nickerchen", "Training"),
            ratingQuestion = "Wie war dieses Gespräch?", reviewText = "Wenn Clockwise dir im Alltag hilft, hilft uns eine Bewertung sehr.", reviewButton = "Clockwise bewerten",
            replyExpectation = "Antwortet meist innerhalb weniger Stunden",
        ),
        "ja" to Copy(
            role = "Clockwise開発者", explanation = "プラン画面を2回閉じた後に自動で送信されました",
            ping = "こんにちは、Rileyさん。Clockwiseを作っているDenizです。今日プラン画面を2回閉じていましたね。Proについてわかりにくい点はありますか？",
            question = "気が変わったら年間プランは返金できますか？",
            ai = "はい。購入から48時間以内の定期購入はGoogle Playが返金します。Playストアを開いてプロフィールをタップし、「お支払いと定期購入」を選んでください。",
            extended = "補足すると、ウィジェットを先に試せるよう体験期間を延長しました。", receipt = "体験期間を11月14日まで延長しました",
            offerTitle = "Pro年間プラン、最初の1か月無料", offerBody = "ウィジェット、無制限のアラーム、すべてのデバイスで同期。", offerButton = "オファーを見る",
            useFor = listOf("仕事の集中", "トレーニング", "料理"),
            photo = "お話ししたウィジェットの不具合です。",
            askReport = "ありがとうございます、確認できました。ウィジェット画面から報告を送ってもらえますか？必要なログが含まれます。",
            reportTitle = "ウィジェットの不具合を報告", reportButton = "不具合を報告", linkTitle = "ウィジェットの更新のしくみ", linkButton = "ガイドを読む",
            timerQuestion = "いちばんよく使うアラームはどれですか？", timers = listOf("平日", "仮眠", "トレーニング"),
            ratingQuestion = "今回のやり取りはいかがでしたか？", reviewText = "Clockwiseが役に立っていたら、レビューをいただけるととても助かります。", reviewButton = "Clockwiseを評価",
            replyExpectation = "通常は数時間以内に返信します",
        ),
        "ar" to Copy(
            role = "صانع Clockwise", explanation = "أُرسلت تلقائيًا بعد أن أغلقت صفحة الخطط مرتين",
            ping = "مرحبًا آدا، أنا دنيز وأصنع Clockwise. أغلقتِ صفحة الخطط مرتين اليوم. هل هناك ما هو غير واضح في Pro؟",
            question = "هل يمكن استرداد ثمن الخطة السنوية إذا غيّرت رأيي؟",
            ai = "نعم. يسترد Google Play الاشتراكات المشتراة خلال آخر 48 ساعة. افتح متجر Play، واضغط على ملفك الشخصي، ثم الدفعات والاشتراكات.",
            extended = "إضافة إلى ذلك: مددتُ فترتك التجريبية لتجربي الأدوات أولًا.", receipt = "مُددت الفترة التجريبية حتى 14 نوفمبر",
            offerTitle = "Pro السنوي، الشهر الأول مجانًا", offerBody = "أدوات، ومنبهات غير محدودة، ومزامنة بين أجهزتك.", offerButton = "عرض العرض",
            useFor = listOf("التركيز في العمل", "التمارين", "الطبخ"),
            photo = "هذا هو خلل الأداة الذي ذكرته.",
            askReport = "شكرًا، أراه الآن. هل يمكنك إرسال بلاغ من شاشة الأداة؟ فهو يتضمن السجلات التي أحتاجها.",
            reportTitle = "الإبلاغ عن خلل الأداة", reportButton = "الإبلاغ عن خطأ", linkTitle = "كيف تتحدث الأدوات", linkButton = "قراءة الدليل",
            timerQuestion = "أي منبه تضبطين أكثر؟", timers = listOf("أيام العمل", "القيلولة", "التمارين"),
            ratingQuestion = "كيف كانت هذه المحادثة؟", reviewText = "إذا كان Clockwise يساعدك في يومك، فتقييمك يساعدنا كثيرًا.", reviewButton = "قيّم Clockwise",
            replyExpectation = "يرد عادةً خلال بضع ساعات",
        ),
        "tr" to Copy(
            role = "Clockwise'ın geliştiricisi", explanation = "Planlar ekranını iki kez kapattıktan sonra otomatik gönderildi",
            ping = "Merhaba Riley, ben Deniz. Clockwise'ı ben yapıyorum. Bugün planlar ekranını iki kez kapattın. Pro ile ilgili net olmayan bir şey var mı?",
            question = "Fikrimi değiştirirsem yıllık planın parasını geri alabilir miyim?",
            ai = "Evet. Google Play son 48 saat içinde alınan abonelikleri iade eder. Play Store'u aç, profiline dokun, sonra Ödemeler ve abonelikler'e gir.",
            extended = "Ek olarak: Önce widget'ları deneyebilmen için deneme süreni uzattım.", receipt = "Deneme süresi 14 Kasım'a uzatıldı",
            offerTitle = "Pro yıllık, ilk ay ücretsiz", offerBody = "Widget'lar, sınırsız alarm ve tüm cihazlarında eşitleme.", offerButton = "Teklife bak",
            useFor = listOf("İşe odaklanma", "Antrenman", "Yemek"),
            photo = "Bahsettiğim widget hatası bu.",
            askReport = "Teşekkürler, görüyorum. Widget ekranından bir rapor gönderebilir misin? İhtiyacım olan kayıtlar raporda olur.",
            reportTitle = "Widget hatasını bildir", reportButton = "Hata bildir", linkTitle = "Widget'lar nasıl yenilenir", linkButton = "Rehberi oku",
            timerQuestion = "En çok hangi alarmı kuruyorsun?", timers = listOf("Hafta içi", "Şekerleme", "Antrenman"),
            ratingQuestion = "Bu sohbet nasıldı?", reviewText = "Clockwise gününe yardımcı oluyorsa, bir yorum bize çok yardımcı olur.", reviewButton = "Clockwise'ı değerlendir",
            replyExpectation = "Genellikle birkaç saat içinde yanıt verir",
        ),
        "fa" to Copy(
            role = "سازندهٔ Clockwise", explanation = "پس از اینکه صفحهٔ طرح‌ها را دو بار بستید، خودکار ارسال شد",
            ping = "سلام Riley، من دنیز هستم و Clockwise را می‌سازم. امروز صفحهٔ طرح‌ها را دو بار بستید. چیزی دربارهٔ Pro نامشخص است؟",
            question = "اگر نظرم عوض شود، هزینهٔ طرح سالانه برگردانده می‌شود؟",
            ai = "بله. Google Play هزینهٔ اشتراک‌هایی را که در ۴۸ ساعت گذشته خریده شده‌اند برمی‌گرداند. فروشگاه Play را باز کنید، روی نمایه‌تان بزنید و بعد «پرداخت‌ها و اشتراک‌ها» را انتخاب کنید.",
            extended = "در ضمن: دورهٔ آزمایشی‌تان را تمدید کردم تا اول ابزارک‌ها را امتحان کنید.", receipt = "دورهٔ آزمایشی تا ۱۴ نوامبر تمدید شد",
            offerTitle = "Pro سالانه، ماه اول رایگان", offerBody = "ابزارک‌ها، هشدارهای نامحدود و همگام‌سازی در همهٔ دستگاه‌هایتان.", offerButton = "دیدن پیشنهاد",
            useFor = listOf("تمرکز در کار", "ورزش", "آشپزی"),
            photo = "این همان اشکال ابزارک است که گفتم.",
            askReport = "ممنون، می‌بینمش. می‌توانید از صفحهٔ ابزارک گزارش بفرستید؟ گزارش‌هایی که لازم دارم در آن هست.",
            reportTitle = "گزارش اشکال ابزارک", reportButton = "گزارش اشکال", linkTitle = "ابزارک‌ها چطور به‌روز می‌شوند", linkButton = "خواندن راهنما",
            timerQuestion = "کدام هشدار را بیشتر تنظیم می‌کنید؟", timers = listOf("روزهای کاری", "چرت", "ورزش"),
            ratingQuestion = "این گفتگو چطور بود؟", reviewText = "اگر Clockwise به روزتان کمک می‌کند، یک نقد به ما خیلی کمک می‌کند.", reviewButton = "امتیاز به Clockwise",
            replyExpectation = "معمولاً ظرف چند ساعت پاسخ می‌دهد",
        ),
        "el" to Copy(
            role = "Δημιουργός του Clockwise", explanation = "Στάλθηκε αυτόματα αφού κλείσατε δύο φορές τα πακέτα",
            ping = "Γεια σας Riley, είμαι ο Deniz και φτιάχνω το Clockwise. Κλείσατε σήμερα δύο φορές την οθόνη με τα πακέτα. Υπάρχει κάτι που δεν είναι σαφές για το Pro;",
            question = "Μπορώ να πάρω πίσω τα χρήματα για το ετήσιο πακέτο αν αλλάξω γνώμη;",
            ai = "Ναι. Το Google Play επιστρέφει χρήματα για συνδρομές που αγοράστηκαν τις τελευταίες 48 ώρες. Ανοίξτε το Play Store, πατήστε το προφίλ σας και μετά Πληρωμές και συνδρομές.",
            extended = "Επιπλέον: επέκτεινα τη δοκιμαστική σας περίοδο για να δοκιμάσετε πρώτα τα widget.", receipt = "Η δοκιμαστική περίοδος επεκτάθηκε έως τις 14 Νοε",
            offerTitle = "Pro ετήσιο, ο πρώτος μήνας δωρεάν", offerBody = "Widget, απεριόριστα ξυπνητήρια και συγχρονισμός σε όλες τις συσκευές σας.", offerButton = "Δείτε την προσφορά",
            useFor = listOf("Συγκέντρωση στη δουλειά", "Γυμναστική", "Μαγείρεμα"),
            photo = "Αυτό είναι το πρόβλημα του widget που ανέφερα.",
            askReport = "Ευχαριστώ, το βλέπω. Μπορείτε να στείλετε μια αναφορά από την οθόνη του widget; Περιέχει τα αρχεία καταγραφής που χρειάζομαι.",
            reportTitle = "Αναφορά προβλήματος widget", reportButton = "Αναφορά σφάλματος", linkTitle = "Πώς ανανεώνονται τα widget", linkButton = "Διαβάστε τον οδηγό",
            timerQuestion = "Ποιο ξυπνητήρι ρυθμίζετε πιο συχνά;", timers = listOf("Καθημερινές", "Υπνάκος", "Γυμναστική"),
            ratingQuestion = "Πώς ήταν αυτή η συζήτηση;", reviewText = "Αν το Clockwise σάς βοηθά στη μέρα σας, μια κριτική μάς βοηθά πολύ.", reviewButton = "Βαθμολογήστε το Clockwise",
            replyExpectation = "Συνήθως απαντά μέσα σε λίγες ώρες",
        ),
    )

    /** Clockwise: teal, rounded font, round corners, one face in the header. */
    public val Clockwise: App = App(
        "clockwise",
        "Clockwise",
        listOf(deniz, mara),
        English,
        OfferSlot(
            "yearly_winback",
            "Pro yearly",
            OfferSlot.Kind.Winback,
            products = listOf(OfferSlot.Product("pro_yearly", "€29.99", "P1Y", "P1M")),
        ),
        "att_widget",
        // Clockwise: one face (Deniz), teal, the system font, round corners, light.
        ChatLook(
            theme = ChatLook.Theme(accent = PingFrontColor("#00796B", "#4DB6AC"), font = FontStyle.System, corners = Corners.Round, scheme = ColorScheme.Light),
            header = ChatLook.Header(style = HeaderStyle.Person),
        ),
    )

    /** Ledgerly: the team (Ana and Tom stacked) in the header, green, serif, soft corners, timestamps on every message. */
    public val Ledgerly: App = App(
        "ledgerly",
        "Ledgerly",
        listOf(ana, tom),
        Copy(
            role = "Makes Ledgerly", explanation = "Sent automatically after your import stopped twice",
            ping = "Hi Sam, Ana here. I run Ledgerly. Your bank import stopped twice this week. Want me to look at the connection?",
            question = "Yes please. Can I export last quarter first?",
            ai = "You can. Open Reports, pick Q3, then Export and choose CSV or PDF. Attachments are included in the PDF.",
            extended = "Done on my side: I reconnected the import and refilled the missing days.", receipt = "Import repaired for 3 accounts",
            offerTitle = "Ledgerly Plus, 30% off", offerBody = "Unlimited accounts, receipts scanning and tax exports.", offerButton = "See the offer",
            useFor = listOf("Freelance", "Household", "Small team"),
            photo = "The PDF export cuts off this table.",
            askReport = "Thanks, I see it. Could you send a report from the export screen? It attaches the log I need.",
            reportTitle = "Report the export problem", reportButton = "Report a bug", linkTitle = "Exporting for your accountant", linkButton = "Read the guide",
            timerQuestion = "How do you mostly use Ledgerly?", timers = listOf("Invoices", "Expenses", "Taxes"),
            ratingQuestion = "How was this conversation?", reviewText = "If Ledgerly saves you time, a review helps us a lot.", reviewButton = "Rate Ledgerly",
            replyExpectation = "Usually replies within a day",
        ),
        OfferSlot(
            "yearly_winback",
            "Plus yearly",
            OfferSlot.Kind.Discount,
            products = listOf(OfferSlot.Product("plus_yearly", "€24.99", "P1Y")),
        ),
        "att_table",
        ChatLook(
            theme = ChatLook.Theme(
                accent = PingFrontColor("#1F6F5C", "#5CC8A8"),
                teamBubble = PingFrontColor("#EEF1EC", "#22302B"),
                font = FontStyle.Serif,
                corners = Corners.Soft,
            ),
            header = ChatLook.Header(
                style = HeaderStyle.Team,
                title = LocalizedText(
                    mapOf(
                        "default" to "The Ledgerly team",
                        "de" to "Das Ledgerly-Team",
                    ),
                ),
            ),
            welcome = ChatLook.Welcome(title = LocalizedText.of("Write to the people who make Ledgerly")),
            topics = listOf(
                ChatLook.Topic("billing", LocalizedText.of("Billing"), TopicSymbol.Billing),
                ChatLook.Topic(
                    "import",
                    LocalizedText.of("Bank import"),
                    TopicSymbol.Bug,
                    LocalizedText.of("My bank import stopped when "),
                ),
                ChatLook.Topic("idea", LocalizedText.of("Feature idea"), TopicSymbol.Idea),
                ChatLook.Topic("privacy", LocalizedText.of("My data"), TopicSymbol.Privacy),
            ),
            messages = ChatLook.Messages(timestamps = Timestamps.Always),
        ),
        person = Author(Author.Kind.Person, "p_sam", "Sam", "fixture://avatar/person-sam"),
    )

    /** Driftwood: coral, soft corners, the app icon in the header, no avatars, an assistant called Guide. */
    public val Driftwood: App = App(
        "driftwood",
        "Driftwood",
        listOf(kofi, lena),
        Copy(
            role = "Makes Driftwood", explanation = "Sent automatically after you skipped two evening sessions",
            ping = "Hey Jo, Kofi from Driftwood. You skipped two evening sessions. Want a shorter wind-down for this week?",
            question = "Yes. Ten minutes is about all I have.",
            ai = "Done: your plan now has a 10-minute wind-down at 22:00, with the breathing exercise first. You can change the time in Plan.",
            extended = "I also added a free week so you can try the shorter plan.", receipt = "Trial extended to 21 Nov",
            offerTitle = "Driftwood Plus, first month free", offerBody = "All soundscapes, sleep stories and offline mode.", offerButton = "See the offer",
            useFor = listOf("Falling asleep", "Focus", "Calm"),
            photo = "The timer shows the wrong length.",
            askReport = "Thanks, that is a bug. Could you send a report from the player screen?",
            reportTitle = "Report the timer problem", reportButton = "Report a bug", linkTitle = "Building a wind-down routine", linkButton = "Read the guide",
            timerQuestion = "When do you usually listen?", timers = listOf("Mornings", "Lunch", "Bedtime"),
            ratingQuestion = "How was this conversation?", reviewText = "If Driftwood helps you rest, a review helps us a lot.", reviewButton = "Rate Driftwood",
            replyExpectation = "We answer every message within a day",
        ),
        OfferSlot(
            "yearly_winback",
            "Plus monthly",
            OfferSlot.Kind.Standard,
            products = listOf(OfferSlot.Product("plus_monthly", "€3.99", "P1M", "P1M")),
        ),
        "att_timer",
        ChatLook(
            // Driftwood: the app icon in the header, coral, the rounded font, dark, compact.
            theme = ChatLook.Theme(
                accent = PingFrontColor("#D9534F", "#FF8A70"),
                font = FontStyle.Rounded,
                corners = Corners.Round,
                density = Density.Compact,
                scheme = ColorScheme.Dark,
            ),
            header = ChatLook.Header(style = HeaderStyle.App, showLocalTime = false),
            messages = ChatLook.Messages(showAvatars = false),
            replyExpectation = LocalizedText.of("We answer every message within a day"),
            aiName = "Guide",
        ),
        person = Author(Author.Kind.Person, "p_jo", "Jo", "fixture://avatar/person-jo"),
    )

    public fun app(id: String): App = when (id) {
        "ledgerly" -> Ledgerly
        "driftwood" -> Driftwood
        else -> Clockwise
    }

    /** A thread that shows every part type. */
    public fun messages(copy: Copy = English, app: App = Clockwise): List<Message> {
        val lead = app.members[0]
        val team = Author(Author.Kind.Team, lead.id, lead.name, lead.avatarUrl)
        val assistant = Author(Author.Kind.Ai, "ai", "${app.name} Assistant")
        val person = app.person
        fun card(card: Card, result: CardResult? = null) = MessagePart.CardPart(card, result)
        return listOf(
            Message(
                "msg_1",
                1,
                author = team,
                parts = listOf(MessagePart.Text(copy.ping)),
                createdAt = at(0.0),
                ping = PingOrigin(
                    PingOrigin.Kind.Play,
                    "play_plans",
                    copy.explanation,
                    PingReason(PingReason.Code.Moment, mapOf("moment" to "paywall_closed")),
                ),
            ),
            Message("msg_2", 2, "6f1d6c2e-2f43-4f7e-9a4b-0d7a3f1c0001", person, listOf(MessagePart.Text(copy.question)), at(4.0)),
            Message("msg_3", 3, author = assistant, parts = listOf(MessagePart.Text(copy.ai)), createdAt = at(4.5)),
            Message("msg_4", 4, author = team, parts = listOf(MessagePart.Text(copy.extended)), createdAt = at(9.0)),
            Message(
                "msg_5",
                5,
                author = Author(Author.Kind.System),
                parts = listOf(MessagePart.Receipt(ReceiptAction.TrialExtended, copy.receipt)),
                createdAt = at(9.2),
            ),
            Message(
                "msg_6",
                6,
                author = team,
                parts = listOf(card(Card.Offer("card_offer", "yearly_winback", copy.offerTitle, copy.offerBody, copy.offerButton))),
                createdAt = at(9.4),
            ),
            Message(
                "msg_7",
                7,
                author = team,
                parts = listOf(
                    card(
                        Card.Choices(
                            "card_choice_1",
                            listOf("work", "sport", "cook").zip(copy.useFor) { id, label ->
                                ChoiceOption(id, label)
                            },
                        ),
                        CardResult(at(11.0), choiceId = "work"),
                    ),
                ),
                createdAt = at(10.0),
            ),
            Message(
                "msg_8",
                8,
                "6f1d6c2e-2f43-4f7e-9a4b-0d7a3f1c0002",
                person,
                listOf(MessagePart.Image(app.photoKey, 1170, 1400, "fixture://image/${app.photoKey}"), MessagePart.Text(copy.photo)),
                at(14.0),
            ),
            Message(
                "msg_9",
                9,
                author = team,
                parts = listOf(MessagePart.Text(copy.askReport), card(Card.Report("card_report", copy.reportTitle, copy.reportButton))),
                createdAt = at(16.0),
            ),
            Message(
                "msg_10",
                10,
                author = team,
                parts = listOf(card(Card.Link("card_link", copy.linkTitle, "https://${app.id}.app/help", copy.linkButton))),
                createdAt = at(16.5),
            ),
            Message(
                "msg_11",
                11,
                author = team,
                parts = listOf(
                    MessagePart.Text(copy.timerQuestion),
                    card(
                        Card.Choices(
                            "card_choice_2",
                            listOf("a", "b", "c").zip(copy.timers) {
                                    id,
                                    label,
                                ->
                                ChoiceOption(id, label)
                            },
                        ),
                    ),
                ),
                createdAt = at(17.0),
            ),
            Message(
                "msg_12",
                12,
                author = team,
                parts = listOf(card(Card.Rating("card_rating", copy.ratingQuestion))),
                createdAt = at(17.2),
            ),
            Message(
                "msg_13",
                13,
                author = team,
                parts = listOf(
                    MessagePart.Text(copy.reviewText),
                    card(
                        Card.Review(
                            "card_review",
                            ReviewMode.NativePrompt,
                            "https://play.google.com/store/apps/details?id=app",
                            copy.reviewButton,
                        ),
                    ),
                ),
                createdAt = at(17.4),
            ),
        )
    }

    /** A message type this SDK version does not know, with the server's fallback text. */
    public val UnknownMessage: Message = Message(
        "msg_unknown",
        14,
        author = Author(Author.Kind.Team, "m_deniz", "Deniz", "fixture://avatar/deniz"),
        parts = listOf(MessagePart.Unknown("poll")),
        createdAt = at(17.5),
        fallback = "Poll: Which alarm sound should we add next?",
    )

    /** Previews: a rate-limit cooldown on [scope] with [remainingMs] left (monotonic, like the engine's). */
    public fun setCooldown(instance: PingFrontInstance, scope: dev.pingfront.RateScope, remainingMs: Long) {
        val deadline = dev.pingfront.internal.runtime.TimeSource.System.now() + kotlin.time.Duration.parse("${remainingMs}ms")
        instance.conversation.update { it.copy(cooldownDeadlines = it.cooldownDeadlines + (scope to deadline)) }
    }

    /** Previews: the live state. */
    public fun setOffline(instance: PingFrontInstance, offline: Boolean = true) {
        instance.conversation.update {
            if (offline) {
                it.copy(live = dev.pingfront.conversation.LiveState.Waiting(kotlin.time.Duration.parse("4s"), offline = true), networkOffline = true)
            } else {
                it.copy(networkOffline = false)
            }
        }
    }

    /**
     * Previews: the thread could not be loaded and nothing is cached. [offline] is what the failed request looked like;
     * [networkOffline] is what the device's network says.
     */
    public fun setLoadFailed(instance: PingFrontInstance, offline: Boolean, networkOffline: Boolean = offline) {
        instance.conversation.update {
            it.copy(
                phase = dev.pingfront.conversation.ConversationPhase.Failed(offline),
                messages = emptyList(),
                pending = emptyList(),
                networkOffline = networkOffline,
            )
        }
    }

    /** Previews: the device has a network, but the server can't be reached. */
    public fun setServerUnreachable(instance: PingFrontInstance) {
        instance.conversation.update { it.copy(networkOffline = true, serverUnreachable = true) }
    }

    /** Tests: a message decoded from wire JSON, exactly as the SDK decodes it. */
    public fun decodeMessage(json: String): Message = dev.pingfront.internal.wire.WireJson.decodeFromString(Message.serializer(), json)

    /** The Play promotional offer the win-back card names, and its price for this person. */
    public val PromoOffer: dev.pingfront.model.StoreOffer =
        dev.pingfront.model.StoreOffer(dev.pingfront.model.StoreOffer.Kind.Promotional, "yearly-40", "pro", "yearly", "yearly-40")

    /**
     * Previews: a win-back message whose card names a Play promotional offer, priced by a stand-in pricer
     * (€17.99 a year, the regular €29.99 struck through).
     */
    public fun addPromoOffer(instance: PingFrontInstance, text: String = "Your Pro plan ended. Come back for 40% off your first year.") {
        instance.registry.offerPricer = object : dev.pingfront.OfferPricer {
            override suspend fun price(offer: dev.pingfront.model.StoreOffer): dev.pingfront.OfferPrice? =
                if (offer.offerId == PromoOffer.offerId) dev.pingfront.OfferPrice("€17.99", "€29.99", "P1Y") else null
        }
        val team = dev.pingfront.model.Author(dev.pingfront.model.Author.Kind.Team, "m_deniz", "Deniz", "fixture://avatar/deniz")
        val parts = listOf(
            dev.pingfront.model.MessagePart.Text(text),
            dev.pingfront.model.MessagePart.CardPart(
                Card.Offer("card_promo", "yearly_winback", "Pro yearly, 40% off", "Widgets, unlimited alarms and sync.", "Get 40% off", PromoOffer),
            ),
        )
        instance.conversation.update {
            it.copy(messages = it.messages + Message("msg_promo", 98, null, team, parts, at(21.0)))
        }
    }

    /** Previews: the app's org is on pingfront's Free plan (`features.branding`). */
    public fun setBranding(instance: PingFrontInstance, on: Boolean = true) {
        val config = instance.previewConfig ?: return
        instance.previewConfig = config.copy(features = config.features.copy(branding = on))
        instance.refreshLook()
    }

    /** Previews: a message from the person with three photos (shown as a grid). */
    public fun addPhotoGrid(instance: PingFrontInstance, app: App = Clockwise) {
        val person = app.person
        val images = (1..3).map { dev.pingfront.model.MessagePart.Image("${app.photoKey}-$it", 1170, 1400, "fixture://image/${app.photoKey}") }
        instance.conversation.update {
            it.copy(messages = it.messages + Message("msg_grid", 99, "6f1d6c2e-2f43-4f7e-9a4b-0d7a3f1c0099", person, images, at(20.0)))
        }
    }

    /** Previews: photos the app's retention removed, one alone and one inside a three-photo grid. */
    public fun addRemovedPhotos(instance: PingFrontInstance, app: App = Clockwise) {
        val person = app.person
        fun image(n: Int, removed: Boolean) = dev.pingfront.model.MessagePart.Image(
            "${app.photoKey}-r$n",
            1170,
            1400,
            if (removed) null else "fixture://image/${app.photoKey}",
            removed = removed,
        )
        val single = Message("msg_removed", 98, "6f1d6c2e-2f43-4f7e-9a4b-0d7a3f1c0098", person, listOf(image(0, true)), at(19.5))
        val grid = Message(
            "msg_removed_grid",
            99,
            "6f1d6c2e-2f43-4f7e-9a4b-0d7a3f1c0099",
            person,
            listOf(image(1, true), image(2, false), image(3, false)),
            at(20.0),
        )
        instance.conversation.update { it.copy(messages = it.messages + single + grid) }
    }

    /** Previews: a message with two photos uploading. */
    public fun setUploading(instance: PingFrontInstance) {
        val photos = (0..1).map { dev.pingfront.conversation.PendingMessage.Photo("photo-up-$it", 1170, 1400, 1000, "sha$it") }
        val pending = dev.pingfront.conversation.PendingMessage(
            "c-up",
            "The widget skips a minute",
            photos,
            createdAt = at(19.0),
            state = dev.pingfront.conversation.PendingMessage.State.Sending,
            attempts = 1,
        )
        instance.conversation.update { it.copy(pending = listOf(pending)) }
    }

    /** Previews: the thread right after the person's first message, before any reply. */
    public fun firstMessageOnly(instance: PingFrontInstance) {
        instance.conversation.update { state -> state.copy(messages = state.messages.filter { it.isFromPerson }.take(1)) }
    }

    /** Tests: a module context for a detached instance (register presenters without configuring the SDK). */
    public fun moduleContext(instance: PingFrontInstance, context: android.content.Context): dev.pingfront.ModuleContext =
        dev.pingfront.ModuleContext(instance, context)

    /** A look with nothing set (code and host decide). */
    public val EmptyLook: ChatLook = ChatLook()

    /** Previews: messages waiting in the outbox, in the given states. */
    public fun setPending(instance: PingFrontInstance, vararg rows: PendingRow) {
        val pending = rows.map {
            dev.pingfront.conversation.PendingMessage(
                it.clientId,
                it.text,
                createdAt = at(it.minutes),
                state = it.state,
                failure = it.failure,
                attempts = it.attempts,
                heldForCode = it.heldFor?.code,
            )
        }
        instance.conversation.update { it.copy(pending = pending) }
    }

    /** One outbox row for [setPending]. */
    public class PendingRow(
        public val clientId: String,
        public val text: String,
        public val minutes: Double,
        public val state: dev.pingfront.conversation.PendingMessage.State = dev.pingfront.conversation.PendingMessage.State.Queued,
        public val failure: String? = null,
        public val attempts: Int = 0,
        /** Held by a closed policy (the app paused, verification required): failed, but going by itself later. */
        public val heldFor: dev.pingfront.model.SendPolicy.Reason? = null,
    )

    /** Previews: the send policy (blocked, paused). */
    public fun setPolicy(instance: PingFrontInstance, policy: dev.pingfront.model.SendPolicy) {
        instance.conversation.update { it.copy(policy = policy) }
    }

    /** Tests: the thread's reply expectation (null: the team set none). */
    public fun setReplyExpectation(instance: PingFrontInstance, text: String?) {
        instance.conversation.update { it.copy(replyExpectation = text) }
    }

    /** Previews: a teammate typing. */
    public fun setTyping(instance: PingFrontInstance, name: String) {
        instance.conversation.update { it.copy(typing = dev.pingfront.conversation.TypingIndicator(name, isAi = false)) }
    }

    /** Tests: the team as a fresh thread load or config push delivers it (a teammate changed name or photo). */
    public fun setTeam(instance: PingFrontInstance, team: List<TeamMember>) {
        instance.conversation.update { it.copy(team = team) }
    }

    /** Previews: older messages loading at the top. */
    public fun setLoadingOlder(instance: PingFrontInstance) {
        instance.conversation.update { it.copy(hasOlder = true, isLoadingOlder = true) }
    }

    /** Previews: the AI teammate hands the conversation to the team; the hand-off line has no green check. */
    public fun addHandOff(instance: PingFrontInstance, app: App = Clockwise) {
        val assistant = Author(Author.Kind.Ai, "ai", "${app.name} Assistant")
        val ask = Message(
            "msg_ai_ask",
            15,
            author = assistant,
            parts = listOf(MessagePart.Text("I can't change billing details myself, so I've passed this to the team.")),
            createdAt = at(18.0),
        )
        val handOff = Message(
            "msg_handoff",
            16,
            author = Author(Author.Kind.System),
            parts = listOf(MessagePart.Receipt(dev.pingfront.model.ReceiptAction.HandedOff, "Deniz will reply")),
            createdAt = at(18.1),
        )
        instance.conversation.update { it.copy(messages = it.messages + ask + handOff) }
    }

    /** Previews: a message with content this SDK version does not know (shows its fallback). */
    public fun addUnknownMessage(instance: PingFrontInstance) {
        instance.conversation.update { it.copy(messages = it.messages + UnknownMessage) }
    }

    /** Tests: exactly these delivered messages. */
    public fun setMessages(instance: PingFrontInstance, messages: List<Message>) {
        instance.conversation.update { it.copy(messages = messages) }
    }

    /** Previews: only the first [count] messages. */
    public fun keepFirst(instance: PingFrontInstance, count: Int) {
        instance.conversation.update { it.copy(messages = it.messages.take(count)) }
    }

    /** Previews: the person's entitlement. */
    public fun setEntitlement(instance: PingFrontInstance, entitlement: dev.pingfront.model.Entitlement) {
        instance.setEntitlement(entitlement)
    }

    public fun team(copy: Copy, app: App = Clockwise): List<TeamMember> = app.members.mapIndexed { i, m ->
        if (i ==
            0
        ) {
            m.copy(role = copy.role)
        } else {
            m
        }
    }

    /** Loads a fixture into [instance] (preview mode: no engine traffic for the chat). */
    public fun load(
        instance: PingFrontInstance,
        kind: Kind,
        language: String = "en",
        app: App = Clockwise,
        look: ChatLook = app.look,
        draft: Boolean = false,
    ) {
        val copy = if (app.id == "clockwise") Copies[language] ?: English else app.copy
        instance.previewConfig = AppConfig(
            revision = 3,
            draft = draft,
            appName = app.name,
            // Each fixture app has its own drawn icon, never another app's or a letter tile (audit X-07).
            appIconUrl = "fixture://icon/${app.id}",
            look = look,
            team = app.members,
            features = AppConfig.Features(photos = true, reports = true, translation = false, ai = true),
        )
        instance.setOffers(listOf(app.offer))
        instance.setTestDevice(draft)
        instance.conversation.update {
            ConversationState(
                phase = ConversationPhase.Ready,
                messages = if (kind == Kind.Conversation) messages(copy, app) else emptyList(),
                team = team(copy, app),
                replyExpectation = copy.replyExpectation,
                teamReadSeq = if (kind == Kind.Conversation) 8 else 0,
            )
        }
        instance.refreshLook()
    }
}

/** The preview launch extra's implementation, found reflectively by `PreviewHooks` (debug builds only). */
internal object DebugPreviewHooks : PreviewHooks {
    override fun load(instance: PingFrontInstance, name: String): Boolean {
        when (name) {
            // A ping arrived while the chat was closed: the app's help row shows the unread badge.
            "help-unread" -> {
                Scenarios.load(instance, "paywall-ping")
                instance.setUnread(1)
                return false
            }
            in Scenarios.names -> Scenarios.load(instance, name)
            "welcome" -> Fixtures.load(instance, Fixtures.Kind.Welcome)
            else -> Fixtures.load(instance, Fixtures.Kind.Conversation)
        }
        return true
    }
}

/**
 * The demo's real-life scenarios as fixture threads (Clockwise, Deniz), for snapshots and the demo's
 * `--es pingfront_preview <name>` launch extra. Mirrors the iOS demo's scenarios where Android allows.
 */
public object Scenarios {
    /** Scenario names, in the demo's order. */
    public val names: List<String> = listOf(
        "paywall-ping",
        "promo",
        "trial-ending",
        "billing-problem",
        "bug-fixed",
        "restore",
        "review",
        "cancel",
        "store-actions",
        "plans",
    )

    private val deniz = Author(Author.Kind.Team, "m_deniz", "Deniz", "fixture://avatar/deniz")
    private val riley = Fixtures.Clockwise.person

    private fun play(explanation: String) = PingOrigin(PingOrigin.Kind.Play, "play_1", explanation)

    private fun message(seq: Int, author: Author, minutes: Double, vararg parts: MessagePart, ping: PingOrigin? = null) = Message(
        "msg_s$seq",
        seq,
        if (author.kind ==
            Author.Kind.Person
        ) {
            "6f1d6c2e-2f43-4f7e-9a4b-0d7a3f1c01${seq.toString().padStart(2, '0')}"
        } else {
            null
        },
        author,
        parts.toList(),
        Fixtures.at(minutes),
        ping,
    )

    private fun text(value: String) = MessagePart.Text(value)

    private fun card(card: Card) = MessagePart.CardPart(card, null)

    /** Loads [name] into [instance]: the Clockwise look and team, then the scenario's thread. */
    public fun load(instance: PingFrontInstance, name: String) {
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        val messages = when (name) {
            "paywall-ping" -> listOf(
                message(
                    1,
                    deniz,
                    0.0,
                    text("Hi Riley, I'm Deniz. I make Clockwise. You closed the plans screen twice today. Anything unclear about Pro?"),
                    card(Card.Offer("card_trial", "yearly_winback", "Pro yearly, first month free", "Widgets, unlimited alarms and sync.", "See the offer")),
                    ping = play("Sent automatically after you closed Plans twice"),
                ),
            )
            "promo" -> emptyList()
            // A different, newer offer replaces the older card: it collapses to one line (snapshots only).
            "offer-replaced" -> listOf(
                message(
                    1,
                    deniz,
                    0.0,
                    text("Hi Riley, I'm Deniz. I make Clockwise. You closed the plans screen twice today. Anything unclear about Pro?"),
                    card(Card.Offer("card_trial", "yearly_winback", "Pro yearly, first month free", "Widgets, unlimited alarms and sync.", "See the offer")),
                    ping = play("Sent automatically after you closed Plans twice"),
                ),
                message(2, riley, 30.0, text("Is there a monthly plan?")),
                message(
                    3,
                    deniz,
                    45.0,
                    text("There is. Here's monthly, with the first week free."),
                    card(Card.Offer("card_monthly", "monthly_intro", "Pro monthly, first week free", null, "See monthly")),
                ),
            )
            "trial-ending" -> listOf(
                message(
                    1,
                    deniz,
                    0.0,
                    text("Your Pro trial ends tomorrow. Want one more week to decide?"),
                    card(Card.Choices("card_week", listOf(ChoiceOption("yes", "Yes please"), ChoiceOption("no", "No thanks")))),
                    ping = play("Sent automatically a day before your trial ends"),
                ),
            )
            "billing-problem" -> listOf(
                message(
                    1,
                    deniz,
                    0.0,
                    text("Your last payment for Pro didn't go through, so Pro will stop on 4 Nov. Google Play can retry with another card."),
                    // A store action: Play's own payment fix (in-app message, else the subscription page).
                    card(Card.StoreAction("card_fix_payment", Card.StoreAction.Action.BillingIssue, productId = "clockwise_pro_yearly")),
                    ping = play("Sent automatically after a payment problem"),
                ),
            )
            "bug-fixed" -> listOf(
                message(1, riley, 0.0, text("The widget skips a minute at midnight.")),
                message(2, deniz, 3.0, MessagePart.Receipt(ReceiptAction.ReportFiled, "Bug report filed")),
                message(3, deniz, 60.0, text("Thanks Riley, found it: the widget rounded 23:59:59 up. Fixed in 2.3, out today.")),
            )
            "restore" -> listOf(
                message(1, riley, 0.0, text("My purchase isn't showing up on my new phone.")),
                message(
                    2,
                    deniz,
                    5.0,
                    text("Found it: your Pro purchase is restored on this phone."),
                    MessagePart.Receipt(ReceiptAction.PurchasesRestored, "Pro restored"),
                ),
            )
            "review" -> listOf(
                message(1, riley, 0.0, text("The widget works perfectly now, thanks!")),
                message(
                    2,
                    deniz,
                    2.0,
                    text("Glad to hear it. If Clockwise helps your day, a review helps us a lot."),
                    card(Card.Review("card_review", ReviewMode.NativePrompt, null, "Rate Clockwise")),
                ),
            )
            "cancel" -> listOf(
                message(
                    1,
                    deniz,
                    0.0,
                    text("Sorry to see you cancel, Riley. What made you decide? It helps me make Clockwise better."),
                    card(
                        Card.Choices(
                            "card_why",
                            listOf(
                                ChoiceOption("price", "Too expensive"),
                                ChoiceOption("feature", "Missing a feature"),
                                ChoiceOption("trying", "Just trying it out"),
                                ChoiceOption("other", "Something else"),
                            ),
                        ),
                    ),
                    ping = play("Sent automatically after you cancelled"),
                ),
            )
            // Every store action card; the restore card already shows its local receipt line.
            "store-actions" -> listOf(
                message(1, riley, 0.0, text("I bought Pro on my old phone and want a refund for the second one.")),
                message(
                    2,
                    deniz,
                    3.0,
                    text("Sorry about that. Restore first, then Google Play handles the refund for the duplicate."),
                    card(Card.StoreAction("card_restore", Card.StoreAction.Action.RestorePurchases)),
                    card(Card.StoreAction("card_refund", Card.StoreAction.Action.RequestRefund)),
                    card(Card.StoreAction("card_manage", Card.StoreAction.Action.ManageSubscriptions, productId = "clockwise_pro_yearly")),
                    card(Card.StoreAction("card_redeem", Card.StoreAction.Action.RedeemCode)),
                ),
            )
            // Play Billing surfaces: a monthly subscriber is offered yearly (a deferred switch), a trial offer with
            // every pricing phase, a purchase waiting for payment, and the plan's status under the manage card.
            "plans" -> listOf(
                message(1, riley, 0.0, text("Is yearly cheaper? And my lifetime purchase says pending.")),
                message(
                    2,
                    deniz,
                    4.0,
                    text("Yearly works out 40% cheaper. Your lifetime purchase is waiting on the bank transfer."),
                    card(Card.Offer("card_switch", "plan_yearly", "Switch to yearly", null, "Switch to yearly")),
                    card(Card.Offer("card_trial", "plan_trial", "Pro yearly, try it first", null, "Start free week")),
                    card(Card.Offer("card_lifetime", "plan_lifetime", "Pro for life", "Pay once, keep it forever.", "Buy for life")),
                    card(Card.StoreAction("card_manage", Card.StoreAction.Action.ManageSubscriptions, productId = "clockwise_pro_monthly")),
                ),
            )
            else -> emptyList()
        }
        if (name == "plans") Plans.install(instance)
        // The server's plan (PersonSummary.entitlement, from store events) carries the dates for the status line.
        serverPlan(name)?.let { instance.setPerson(dev.pingfront.model.PersonSummary("p_riley", dev.pingfront.model.PersonSummary.Level.Named, "Riley", it)) }
        instance.conversation.update {
            it.copy(
                messages = messages,
                teamReadSeq = messages.maxOfOrNull { m -> m.seq } ?: 0,
                storeReceipts = if (name == "store-actions") mapOf("card_restore" to dev.pingfront.conversation.StoreReceipt.Restored) else emptyMap(),
            )
        }
        if (name == "promo") Fixtures.addPromoOffer(instance)
    }
}

/** The server's plan per scenario: grace until 4 Nov for the payment problem, renewing on 12 Nov for plans. */
internal fun serverPlan(name: String): dev.pingfront.model.Entitlement? = when (name) {
    "billing-problem" -> dev.pingfront.model.Entitlement(
        dev.pingfront.model.Entitlement.State.Grace,
        "clockwise_pro_yearly",
        source = dev.pingfront.model.Entitlement.Source.Play,
        gracePeriodEndsAt = java.time.Instant.parse("2026-11-04T09:00:00Z"),
    )
    "plans" -> dev.pingfront.model.Entitlement(
        dev.pingfront.model.Entitlement.State.Active,
        "clockwise_pro_monthly",
        java.time.Instant.parse("2026-11-12T09:00:00Z"),
        dev.pingfront.model.Entitlement.Source.Play,
    )
    else -> null
}

/** Fixture store for the "plans" scenario: what Play would report, without Play. */
internal object Plans {
    private val monthly = OfferSlot("plan_monthly", "Pro monthly", products = listOf(OfferSlot.Product("clockwise_pro_monthly", "€3.99", "P1M")))
    private val yearly = OfferSlot("plan_yearly", "Pro yearly", products = listOf(OfferSlot.Product("clockwise_pro_yearly", "€29.99", "P1Y")))
    private val trial = OfferSlot("plan_trial", "Pro yearly", products = listOf(OfferSlot.Product("clockwise_pro_yearly_trial", "€29.99", "P1Y", "P1W")))
    private val lifetime = OfferSlot("plan_lifetime", "Pro for life", products = listOf(OfferSlot.Product("clockwise_pro_lifetime", "€59.99")))

    fun install(instance: PingFrontInstance) {
        instance.setOffers(listOf(monthly, yearly, trial, lifetime))
        instance.setEntitlement(
            dev.pingfront.model.Entitlement(
                dev.pingfront.model.Entitlement.State.Active,
                "clockwise_pro_monthly",
                java.time.Instant.parse("2026-11-12T09:00:00Z"),
                dev.pingfront.model.Entitlement.Source.Play,
            ),
        )
        instance.registry.offerPricer = object : dev.pingfront.OfferPricer {
            override suspend fun price(offer: dev.pingfront.model.StoreOffer): dev.pingfront.OfferPrice? = offer.productId?.let { price(it) }

            override suspend fun price(productId: String): dev.pingfront.OfferPrice? = when (productId) {
                // Monthly to yearly costs less per year, so Play defers it to the renewal.
                "clockwise_pro_yearly" -> dev.pingfront.OfferPrice(
                    "€29.99",
                    null,
                    "P1Y",
                    listOf(dev.pingfront.PricePhase("€29.99", "P1Y", null, free = false)),
                    dev.pingfront.PlanChange.AtRenewal,
                )
                "clockwise_pro_yearly_trial" -> dev.pingfront.OfferPrice(
                    "€1.99",
                    null,
                    "P1M",
                    listOf(
                        dev.pingfront.PricePhase("Free", "P1W", 1, free = true),
                        dev.pingfront.PricePhase("€1.99", "P1M", 3, free = false),
                        dev.pingfront.PricePhase("€29.99", "P1Y", null, free = false),
                    ),
                )
                else -> null
            }
        }
        instance.registry.purchaseRefresher = dev.pingfront.PurchaseRefresher {
            dev.pingfront.StorePurchases(
                instance.entitlement.value ?: dev.pingfront.model.Entitlement(dev.pingfront.model.Entitlement.State.None),
                setOf("clockwise_pro_lifetime"),
            )
        }
    }
}
