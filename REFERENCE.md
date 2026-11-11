# pingfront Android SDK: reference

Every public declaration, generated from the API dumps that `apiCheck` enforces
([scripts/generate-reference.py](scripts/generate-reference.py)). Internal API (`@InternalPingFrontApi`) is excluded. Kotlin
properties appear as their getters. See README.md for usage.

## `pingfront-core`

### `dev.pingfront.AppKey`

- `getMode(): AppKey.Mode`
- `getRawValue(): String`

### `dev.pingfront.AppKey.Companion`

- `parse(String): Object`
- `problem(String): KeyProblem`

### `dev.pingfront.AppKey.Mode`

- `getEntries(): EnumEntries`
- `valueOf(String): AppKey.Mode`
- `values(): AppKey.Mode[]`

### `dev.pingfront.AppScreen`

- `constructor(String, String)`
- `getId(): String`
- `getTitle(): String`

### `dev.pingfront.CardKind`

- `getName(): String`

### `dev.pingfront.CardKind.Companion`

- `getChoices(): String`
- `getLink(): String`
- `getOffer(): String`
- `getRating(): String`
- `getReport(): String`
- `getReview(): String`
- `getStoreAction(): String`
- `of(Card): String`

### `dev.pingfront.ChatPresenter`

- `dismissChat(): Unit`
- `openLink(String): Boolean`
- `openUrl(String): Unit`
- `presentChat(ChatTopic): Unit`
- `presentSimpleReport(String): Unit`

### `dev.pingfront.ChatPresenter.DefaultImpls`

- `openLink(ChatPresenter, String): Boolean`

### `dev.pingfront.ConfigurationProblem`

- `getDisablesSdk(): Boolean`
- `getKind(): ConfigurationProblem.Kind`
- `getMessage(): String`

### `dev.pingfront.ConfigurationProblem.Kind`


### `dev.pingfront.ConfigurationProblem.Kind.AppPaused`


### `dev.pingfront.ConfigurationProblem.Kind.InvalidKey`

- `getProblem(): KeyProblem`

### `dev.pingfront.ConfigurationProblem.Kind.MissingKey`


### `dev.pingfront.ConfigurationProblem.Kind.Option`


### `dev.pingfront.ConfigurationProblem.Kind.UnknownKey`


### `dev.pingfront.EntitlementSource`

- `entitlementUpdates(): Flow`

### `dev.pingfront.ErrorCode`

- `getRaw(): String`

### `dev.pingfront.ErrorCode.Companion`

- `getAppPaused(): String`
- `getBlocked(): String`
- `getConflict(): String`
- `getForbidden(): String`
- `getIdentityUserMismatch(): String`
- `getInvalidAppKey(): String`
- `getInvalidRequest(): String`
- `getNotFound(): String`
- `getRateLimited(): String`
- `getSessionRevoked(): String`
- `getThreadReset(): String`
- `getTooLarge(): String`
- `getUnauthorized(): String`
- `getUnavailable(): String`
- `getUploadIncomplete(): String`
- `getVerificationRequired(): String`

### `dev.pingfront.FieldError`

- `getMessage(): String`
- `getPath(): String`

### `dev.pingfront.IdentityToken`

- `getJwt(): String`

### `dev.pingfront.InternalPingFrontApi`


### `dev.pingfront.KeyProblem`

- `getCode(): String`
- `getEntries(): EnumEntries`
- `getMessage(): String`
- `valueOf(String): KeyProblem`
- `values(): KeyProblem[]`

### `dev.pingfront.LogEntry`

- `getCategory(): String`
- `getLevel(): LogLevel`
- `getMessage(): String`

### `dev.pingfront.LogHandler`

- `log(LogEntry): Unit`

### `dev.pingfront.LogLevel`

- `getEntries(): EnumEntries`
- `valueOf(String): LogLevel`
- `values(): LogLevel[]`

### `dev.pingfront.ModuleContext`

- `addEntitlementSource(EntitlementSource): Unit`
- `addOfferSource(OfferSource): Unit`
- `currentActivity(): Activity`
- `getApplicationContext(): Context`
- `getIoDispatcher(): CoroutineDispatcher`
- `getMainDispatcher(): CoroutineDispatcher`
- `getScope(): CoroutineScope`
- `setChatPresenter(ChatPresenter): Unit`
- `setHostApp(String, PingFrontColor, Byte[]): Unit`
- `setOfferHandler(OfferHandler): Unit`
- `setOfferPricer(OfferPricer): Unit`
- `setPaymentFixer(PaymentFixer): Unit`
- `setPurchaseRefresher(PurchaseRefresher): Unit`
- `setPushTokenSource(PushTokenSource): Unit`
- `setReportContextProvider(ReportContextProvider): Unit`
- `setReportPresenter(ReportPresenter): Unit`
- `setReviewRequester(ReviewRequester): Unit`

### `dev.pingfront.MomentResult`


### `dev.pingfront.MomentResult.Delivered`


### `dev.pingfront.MomentResult.Failed`

- `getError(): PingFrontException`

### `dev.pingfront.MomentResult.HeldOut`


### `dev.pingfront.MomentResult.Ignored`


### `dev.pingfront.MomentResult.InvalidName`


### `dev.pingfront.MomentResult.NotListened`


### `dev.pingfront.MomentResult.OpenRequested`

- `getPresented(): Boolean`

### `dev.pingfront.MomentResult.Scheduled`


### `dev.pingfront.OfferFailure`

- `getCode(): String`

### `dev.pingfront.OfferFailure.Companion`

- `getBillingUnavailable(): String`
- `getInProgress(): String`
- `getNoActivity(): String`
- `getProductNotFound(): String`
- `getStoreError(): String`

### `dev.pingfront.OfferHandler`

- `handle(OfferRequest, Continuation): Object`

### `dev.pingfront.OfferOutcome`


### `dev.pingfront.OfferOutcome.Cancelled`


### `dev.pingfront.OfferOutcome.Failed`

- `getFailure(): String`

### `dev.pingfront.OfferOutcome.Pending`


### `dev.pingfront.OfferOutcome.Purchased`


### `dev.pingfront.OfferOutcome.Unavailable`


### `dev.pingfront.OfferPrice`

- `constructor(String, String, String, List, PlanChange)`
- `getChange(): PlanChange`
- `getPeriod(): String`
- `getPhases(): List`
- `getPrice(): String`
- `getRegularPrice(): String`

### `dev.pingfront.OfferPricer`

- `price(StoreOffer, Continuation): Object`
- `price(String, Continuation): Object`

### `dev.pingfront.OfferPricer.DefaultImpls`

- `price(OfferPricer, String, Continuation): Object`

### `dev.pingfront.OfferRequest`

- `getCardId(): String`
- `getMessageId(): String`
- `getOfferId(): String`
- `getSlot(): OfferSlot`
- `getStoreOffer(): StoreOffer`

### `dev.pingfront.OfferSource`

- `offers(Continuation): Object`

### `dev.pingfront.OpenedCard`

- `getCardId(): String`
- `getKind(): String`
- `getMessageId(): String`

### `dev.pingfront.PaymentFix`

- `getEntries(): EnumEntries`
- `valueOf(String): PaymentFix`
- `values(): PaymentFix[]`

### `dev.pingfront.PaymentFixer`

- `showPaymentFix(Continuation): Object`

### `dev.pingfront.PingFront`

- `closeChat(): Unit`
- `closeChatAndWait(Continuation): Object`
- `configure(Context): Unit`
- `configure(Context, String): Unit`
- `configure(Context, String, Function1): Unit`
- `eraseMyData(Continuation): Object`
- `exportMyData(Continuation): Object`
- `getConfigurationProblem(): StateFlow`
- `getConversation(): Conversation`
- `getEvents(): Flow`
- `getLanguage(): String`
- `getLook(): StateFlow`
- `getOnLink(): Function2`
- `getOnOffer(): Function1`
- `getOnScreen(): Function2`
- `getPerson(): StateFlow`
- `getTeamActions(): Flow`
- `getUnreadCount(): StateFlow`
- `getUsesAppBadge(): Boolean`
- `handle(Intent): Boolean`
- `handle(Uri): Boolean`
- `handlePush(Map): Boolean`
- `identify(String): Unit`
- `identify(String, String): Unit`
- `identify(String, String, String): Unit`
- `identifyWithToken(String): Unit`
- `isChatVisible(): StateFlow`
- `isConfigured(): StateFlow`
- `isPingFrontPush(Map): Boolean`
- `isTestDevice(): StateFlow`
- `logout(): Unit`
- `moment(String, Map, Continuation): Object`
- `openChat(): Unit`
- `openChat(ChatTopic): Unit`
- `openPush(Map): Boolean`
- `openReport(): Unit`
- `openReport(String): Unit`
- `prepare(Continuation): Object`
- `registerScreens(List): Unit`
- `reportBug(String, Byte[], Continuation): Object`
- `selfCheck(Continuation): Object`
- `setEntitlement(Entitlement): Unit`
- `setLanguage(String): Unit`
- `setOffers(List): Unit`
- `setOnLink(Function2): Unit`
- `setOnOffer(Function1): Unit`
- `setOnScreen(Function2): Unit`
- `setPushToken(String): Unit`
- `setTraits(Map): Unit`
- `storeAccountToken(Continuation): Object`

### `dev.pingfront.PingFrontConfig`

- `appearance(Function1): Unit`
- `constructor()`
- `getAppearance(): ChatAppearance`
- `getBaseUrl(): String`
- `getLanguage(): String`
- `getLogHandler(): LogHandler`
- `getLogLevel(): LogLevel`
- `getModules(): List`
- `getPresentsChatForMoments(): Boolean`
- `setBaseUrl(String): Unit`
- `setLanguage(String): Unit`
- `setLogHandler(LogHandler): Unit`
- `setLogLevel(LogLevel): Unit`
- `setPresentsChatForMoments(Boolean): Unit`
- `topics(ChatTopic[]): Unit`
- `topics(List): Unit`
- `use(PingFrontModule[]): Unit`

### `dev.pingfront.PingFrontDsl`


### `dev.pingfront.PingFrontEvent`


### `dev.pingfront.PingFrontEvent.CardOpened`

- `getCard(): OpenedCard`

### `dev.pingfront.PingFrontEvent.ChatClosed`


### `dev.pingfront.PingFrontEvent.ChatOpened`


### `dev.pingfront.PingFrontEvent.ConfigurationProblemFound`

- `getProblem(): ConfigurationProblem`

### `dev.pingfront.PingFrontEvent.IdentityProblemReported`

- `getProblem(): IdentityProblem`

### `dev.pingfront.PingFrontEvent.LookChanged`

- `getDraft(): Boolean`
- `getRevision(): Int`

### `dev.pingfront.PingFrontEvent.MessageReceived`

- `getMessage(): ReceivedMessage`

### `dev.pingfront.PingFrontEvent.MessageSent`

- `getMessage(): SentMessage`

### `dev.pingfront.PingFrontEvent.OfferCompleted`

- `getOutcome(): OfferOutcome`
- `getRequest(): OfferRequest`

### `dev.pingfront.PingFrontEvent.OfferRequested`

- `getRequest(): OfferRequest`

### `dev.pingfront.PingFrontEvent.ReportSent`


### `dev.pingfront.PingFrontEvent.SessionRevoked`


### `dev.pingfront.PingFrontEvent.SessionStarted`

- `getPerson(): PersonSummary`

### `dev.pingfront.PingFrontEvent.UnreadChanged`

- `getCount(): Int`

### `dev.pingfront.PingFrontException`

- `getCode(): String`
- `getLogDescription(): String`
- `isOffline(): Boolean`
- `isTransient(): Boolean`

### `dev.pingfront.PingFrontException.CoolingDown`

- `getRemaining(): Long`
- `getScope(): String`

### `dev.pingfront.PingFrontException.InvalidInput`

- `constructor(String)`

### `dev.pingfront.PingFrontException.InvalidKey`

- `constructor(KeyProblem)`
- `getProblem(): KeyProblem`

### `dev.pingfront.PingFrontException.InvalidResponse`

- `constructor(String, Throwable)`

### `dev.pingfront.PingFrontException.Network`

- `constructor(Boolean, Throwable)`
- `getOffline(): Boolean`

### `dev.pingfront.PingFrontException.NotConfigured`

- `constructor()`

### `dev.pingfront.PingFrontException.Server`

- `constructor(ServerError)`
- `getError(): ServerError`

### `dev.pingfront.PingFrontException.SessionChanged`

- `constructor()`

### `dev.pingfront.PingFrontException.StorageUnavailable`

- `constructor()`
- `constructor(Throwable)`

### `dev.pingfront.PingFrontInstance.Companion`

- `detached(): PingFrontInstance`

### `dev.pingfront.PingFrontModule`

- `constructor(String)`
- `getId(): String`
- `install(ModuleContext): Unit`

### `dev.pingfront.PlanChange`

- `getEntries(): EnumEntries`
- `valueOf(String): PlanChange`
- `values(): PlanChange[]`

### `dev.pingfront.PricePhase`

- `constructor(String, String, Integer, Boolean)`
- `getCycles(): Integer`
- `getFree(): Boolean`
- `getPeriod(): String`
- `getPrice(): String`

### `dev.pingfront.PurchaseRefresher`

- `refresh(Continuation): Object`

### `dev.pingfront.PushPayload.Companion`

- `parse(Map): PushPayload`

### `dev.pingfront.PushPayload.Companion.Keys`


### `dev.pingfront.PushTokenSource`

- `currentToken(Continuation): Object`

### `dev.pingfront.RateScope`

- `getCode(): String`

### `dev.pingfront.RateScope.Companion`

- `getExports(): String`
- `getLive(): String`
- `getMessages(): String`
- `getMoments(): String`
- `getSessions(): String`
- `getUploads(): String`

### `dev.pingfront.ReceivedMessage`

- `getAuthorName(): String`
- `getId(): String`
- `getPreview(): String`
- `isAi(): Boolean`
- `isPing(): Boolean`

### `dev.pingfront.ReportContext`

- `constructor()`
- `constructor(String, Map)`
- `getLogTail(): String`
- `getState(): Map`

### `dev.pingfront.ReportContextProvider`

- `reportContext(Continuation): Object`

### `dev.pingfront.ReportPresenter`

- `presentReport(String): Unit`

### `dev.pingfront.ReviewRequester`

- `requestReview(Continuation): Object`

### `dev.pingfront.SentMessage`

- `getId(): String`
- `getPhotoCount(): Int`
- `getRepliesToPing(): Boolean`
- `getTextLength(): Int`

### `dev.pingfront.ServerError`

- `getCode(): String`
- `getDocs(): URI`
- `getFields(): List`
- `getMessage(): String`
- `getRetryAfter(): Duration`
- `getScope(): String`
- `getStatus(): Int`

### `dev.pingfront.SetupCheckResult`

- `getAdvice(): String`
- `getCode(): String`
- `getLine(): String`
- `getPassed(): Boolean`

### `dev.pingfront.SetupCheckResult.Companion`

- `getPass(): SetupCheckResult`

### `dev.pingfront.StoreMomentProps`

- `childSerializers(): KSerializer[]`
- `constructor(String, String, String, String, String, String, String, String)`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): StoreMomentProps`
- `getCurrency(): String`
- `getDescriptor(): SerialDescriptor`
- `getOrderId(): String`
- `getOriginalTransactionId(): String`
- `getPrice(): String`
- `getProductId(): String`
- `getPurchaseToken(): String`
- `getSignedTransaction(): String`
- `getTransactionId(): String`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, StoreMomentProps): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.StoreMomentProps.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.StorePurchases`

- `constructor(Entitlement, Set)`
- `getEntitlement(): Entitlement`
- `getPendingProducts(): Set`

### `dev.pingfront.TeamAction`

- `complete(): Unit`
- `fail(String): Unit`
- `getAckRequired(): Boolean`
- `getActionId(): String`
- `getCreatedAt(): Instant`
- `getDays(): Integer`
- `getExpiresAt(): Instant`
- `getFields(): Map`
- `getId(): String`
- `getName(): String`
- `getUntil(): Instant`

### `dev.pingfront.conversation.Conversation`

- `choose(ChoiceOption, Card.Choices, Message): Unit`
- `consumeInsertion(): Unit`
- `cooldownRemaining(String): Duration`
- `discard(PendingMessage): Unit`
- `getComposerInsertion(): StateFlow`
- `getDraft(): StateFlow`
- `getState(): StateFlow`
- `loadOlder(): Unit`
- `markRead(): Unit`
- `open(Card, Message): Unit`
- `photoData(PendingMessage.Photo, Continuation): Object`
- `rate(Int, Card.Rating, Message): Unit`
- `refreshedUrl(MessagePart.Image, Continuation): Object`
- `reload(Continuation): Object`
- `retry(PendingMessage): Unit`
- `send(String, List): Unit`
- `setTyping(Boolean): Unit`
- `updateDraft(String): Unit`

### `dev.pingfront.conversation.ConversationPhase`


### `dev.pingfront.conversation.ConversationPhase.Failed`

- `getOffline(): Boolean`

### `dev.pingfront.conversation.ConversationPhase.Idle`


### `dev.pingfront.conversation.ConversationPhase.Loading`


### `dev.pingfront.conversation.ConversationPhase.Ready`


### `dev.pingfront.conversation.ConversationState`

- `constructor()`
- `getCoolingDown(): Set`
- `getHasOlder(): Boolean`
- `getLive(): LiveState`
- `getMessages(): List`
- `getPending(): List`
- `getPhase(): ConversationPhase`
- `getPolicy(): SendPolicy`
- `getReplyExpectation(): String`
- `getStatus(): ThreadStatus`
- `getStoreReceipts(): Map`
- `getTeam(): List`
- `getTeamReadSeq(): Int`
- `getTimeline(): List`
- `getTyping(): TypingIndicator`
- `isEmpty(): Boolean`
- `isLoadingOlder(): Boolean`
- `isOffline(): Boolean`
- `isServerUnreachable(): Boolean`

### `dev.pingfront.conversation.LiveState`


### `dev.pingfront.conversation.LiveState.Connecting`


### `dev.pingfront.conversation.LiveState.Live`


### `dev.pingfront.conversation.LiveState.Stopped`


### `dev.pingfront.conversation.LiveState.Waiting`

- `getOffline(): Boolean`
- `getRetryIn(): Long`

### `dev.pingfront.conversation.PendingMessage`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): PendingMessage`
- `getAttempts(): Int`
- `getClientId(): String`
- `getCreatedAt(): Instant`
- `getDescriptor(): SerialDescriptor`
- `getFailure(): String`
- `getHeldFor(): String`
- `getPhotos(): List`
- `getState(): PendingMessage.State`
- `getText(): String`
- `isHeld(): Boolean`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, PendingMessage): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.conversation.PendingMessage.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.conversation.PendingMessage.Photo`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): PendingMessage.Photo`
- `getAttachmentId(): String`
- `getBytes(): Int`
- `getDescriptor(): SerialDescriptor`
- `getHeight(): Int`
- `getSha256(): String`
- `getWidth(): Int`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, PendingMessage.Photo): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.conversation.PendingMessage.Photo.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.conversation.PendingMessage.State`

- `getEntries(): EnumEntries`
- `valueOf(String): PendingMessage.State`
- `values(): PendingMessage.State[]`

### `dev.pingfront.conversation.StoreReceipt`

- `getEntries(): EnumEntries`
- `valueOf(String): StoreReceipt`
- `values(): StoreReceipt[]`

### `dev.pingfront.conversation.TimelineItem`

- `getAuthorName(): String`
- `getContent(): TimelineItem.Content`
- `getDate(): Instant`
- `getEndsGroup(): Boolean`
- `getId(): String`
- `getSide(): TimelineItem.Side`
- `getStartsGroup(): Boolean`
- `getStartsPing(): Boolean`
- `getStartsTimeSection(): Boolean`
- `isAi(): Boolean`

### `dev.pingfront.conversation.TimelineItem.Content`


### `dev.pingfront.conversation.TimelineItem.Content.Delivered`

- `getMessage(): Message`

### `dev.pingfront.conversation.TimelineItem.Content.Pending`

- `getMessage(): PendingMessage`

### `dev.pingfront.conversation.TimelineItem.Side`

- `getEntries(): EnumEntries`
- `valueOf(String): TimelineItem.Side`
- `values(): TimelineItem.Side[]`

### `dev.pingfront.conversation.TypingIndicator`

- `getName(): String`
- `isAi(): Boolean`

### `dev.pingfront.internal.android.PingFrontInitializer`

- `constructor()`
- `create(Context): Object`
- `create(Context): Unit`
- `dependencies(): List`

### `dev.pingfront.look.ChatAppearance`

- `constructor()`
- `getAccent(): PingFrontColor`
- `getAiName(): String`
- `getBackground(): PingFrontColor`
- `getComposerPlaceholder(): String`
- `getCorners(): Corners`
- `getDensity(): Density`
- `getFont(): FontStyle`
- `getHeaderMemberId(): String`
- `getHeaderStyle(): HeaderStyle`
- `getHeaderTitle(): String`
- `getPhotos(): Boolean`
- `getReplyExpectation(): String`
- `getReports(): Boolean`
- `getScheme(): ColorScheme`
- `getShowAvatars(): Boolean`
- `getShowLocalTime(): Boolean`
- `getShowNames(): Boolean`
- `getShowReplyExpectation(): Boolean`
- `getShowWelcomeFaces(): Boolean`
- `getTeamBubble(): PingFrontColor`
- `getTimestamps(): Timestamps`
- `getUseDynamicColor(): Boolean`
- `getWelcomeBody(): String`
- `getWelcomeTitle(): String`
- `setAccent(PingFrontColor): Unit`
- `setAiName(String): Unit`
- `setBackground(PingFrontColor): Unit`
- `setComposerPlaceholder(String): Unit`
- `setCorners(Corners): Unit`
- `setDensity(Density): Unit`
- `setFont(FontStyle): Unit`
- `setHeaderMemberId(String): Unit`
- `setHeaderStyle(HeaderStyle): Unit`
- `setHeaderTitle(String): Unit`
- `setPhotos(Boolean): Unit`
- `setReplyExpectation(String): Unit`
- `setReports(Boolean): Unit`
- `setScheme(ColorScheme): Unit`
- `setShowAvatars(Boolean): Unit`
- `setShowLocalTime(Boolean): Unit`
- `setShowNames(Boolean): Unit`
- `setShowReplyExpectation(Boolean): Unit`
- `setShowWelcomeFaces(Boolean): Unit`
- `setTeamBubble(PingFrontColor): Unit`
- `setTimestamps(Timestamps): Unit`
- `setUseDynamicColor(Boolean): Unit`
- `setWelcomeBody(String): Unit`
- `setWelcomeTitle(String): Unit`

### `dev.pingfront.look.ChatTopic`

- `constructor(String, LocalizedText, TopicSymbol, LocalizedText)`
- `constructor(String, String, TopicSymbol, String)`
- `getId(): String`
- `getLabel(): LocalizedText`
- `getPrefill(): LocalizedText`
- `getSymbol(): TopicSymbol`

### `dev.pingfront.look.ChatTopic.Companion`

- `getAccount(): ChatTopic`
- `getBilling(): ChatTopic`
- `getBug(): ChatTopic`
- `getDefaults(): List`
- `getFeedback(): ChatTopic`
- `getGift(): ChatTopic`
- `getIdea(): ChatTopic`
- `getPrivacy(): ChatTopic`
- `getQuestion(): ChatTopic`
- `getRestore(): ChatTopic`

### `dev.pingfront.look.PngImage`

- `constructor(Byte[])`
- `getBytes(): Byte[]`

### `dev.pingfront.look.ResolvedLook`

- `getAccent(): PingFrontColor`
- `getAiName(): String`
- `getAppIcon(): PngImage`
- `getAppIconUrl(): String`
- `getAppName(): String`
- `getBackground(): PingFrontColor`
- `getBubbleRadius(): Int`
- `getCardRadius(): Int`
- `getComposerPlaceholder(): LocalizedText`
- `getCorners(): Corners`
- `getDensity(): Density`
- `getFont(): FontStyle`
- `getHeaderMemberId(): String`
- `getHeaderStyle(): HeaderStyle`
- `getHeaderTitle(): LocalizedText`
- `getMuted(): PingFrontColor`
- `getOnAccent(): PingFrontColor`
- `getPhotos(): Boolean`
- `getReplyExpectation(): LocalizedText`
- `getReports(): Boolean`
- `getRevision(): Int`
- `getScheme(): ColorScheme`
- `getShowAvatars(): Boolean`
- `getShowLocalTime(): Boolean`
- `getShowNames(): Boolean`
- `getShowReplyExpectation(): Boolean`
- `getShowWelcomeFaces(): Boolean`
- `getTeam(): List`
- `getTeamBubble(): PingFrontColor`
- `getText(): PingFrontColor`
- `getTimestamps(): Timestamps`
- `getTopics(): List`
- `getWelcomeBody(): LocalizedText`
- `getWelcomeTitle(): LocalizedText`
- `isDraft(): Boolean`

### `dev.pingfront.model.Author`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Author`
- `deserialize(Decoder): Object`
- `getAvatarUrl(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getKind(): Author.Kind`
- `getName(): String`
- `serialize(Encoder, Author): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Author.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Author.Kind`

- `getEntries(): EnumEntries`
- `valueOf(String): Author.Kind`
- `values(): Author.Kind[]`

### `dev.pingfront.model.Author.Kind.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card`

- `getId(): String`

### `dev.pingfront.model.Card.Choices`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.Choices`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getOptions(): List`
- `serialize(Encoder, Card.Choices): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.Choices.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Link`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.Link`
- `deserialize(Decoder): Object`
- `getButton(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getSavedLinkId(): String`
- `getScreen(): LinkScreen`
- `getTitle(): String`
- `getUrl(): String`
- `serialize(Encoder, Card.Link): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.Link.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Offer`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.Offer`
- `deserialize(Decoder): Object`
- `getBody(): String`
- `getButton(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getOffer(): StoreOffer`
- `getOfferId(): String`
- `getTitle(): String`
- `serialize(Encoder, Card.Offer): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.Offer.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Rating`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.Rating`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getQuestion(): String`
- `getScale(): Int`
- `serialize(Encoder, Card.Rating): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.Rating.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Report`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.Report`
- `deserialize(Decoder): Object`
- `getButton(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getTitle(): String`
- `serialize(Encoder, Card.Report): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.Report.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Review`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.Review`
- `deserialize(Decoder): Object`
- `getButton(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getMode(): ReviewMode`
- `getStoreUrl(): String`
- `serialize(Encoder, Card.Review): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.Review.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.StoreAction`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Card.StoreAction`
- `deserialize(Decoder): Object`
- `getAction(): Card.StoreAction.Action`
- `getBody(): String`
- `getButton(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getProductId(): String`
- `getStore(): String`
- `getSubscriptionGroupId(): String`
- `getTitle(): String`
- `getTransactionId(): String`
- `serialize(Encoder, Card.StoreAction): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Card.StoreAction.Action`

- `getEntries(): EnumEntries`
- `valueOf(String): Card.StoreAction.Action`
- `values(): Card.StoreAction.Action[]`

### `dev.pingfront.model.Card.StoreAction.Action.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.StoreAction.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Card.Unknown`

- `getId(): String`
- `getKind(): String`

### `dev.pingfront.model.CardResult`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): CardResult`
- `deserialize(Decoder): Object`
- `getAt(): Instant`
- `getChoiceId(): String`
- `getDescriptor(): SerialDescriptor`
- `getRating(): Integer`
- `serialize(Encoder, CardResult): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.CardResult.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook`

- `constructor()`
- `getAiName(): String`
- `getComposer(): ChatLook.Composer`
- `getHeader(): ChatLook.Header`
- `getMessages(): ChatLook.Messages`
- `getReplyExpectation(): LocalizedText`
- `getTheme(): ChatLook.Theme`
- `getTopics(): List`
- `getWelcome(): ChatLook.Welcome`

### `dev.pingfront.model.ChatLook.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook.Composer`

- `childSerializers(): KSerializer[]`
- `constructor()`
- `deserialize(Decoder): ChatLook.Composer`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getPhotos(): Boolean`
- `getPlaceholder(): LocalizedText`
- `getReports(): Boolean`
- `serialize(Encoder, ChatLook.Composer): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChatLook.Composer.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook.Header`

- `childSerializers(): KSerializer[]`
- `constructor()`
- `deserialize(Decoder): ChatLook.Header`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getMemberId(): String`
- `getShowLocalTime(): Boolean`
- `getShowReplyExpectation(): Boolean`
- `getStyle(): HeaderStyle`
- `getTitle(): LocalizedText`
- `serialize(Encoder, ChatLook.Header): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChatLook.Header.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook.Messages`

- `childSerializers(): KSerializer[]`
- `constructor()`
- `deserialize(Decoder): ChatLook.Messages`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getShowAvatars(): Boolean`
- `getShowNames(): Boolean`
- `getTimestamps(): Timestamps`
- `serialize(Encoder, ChatLook.Messages): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChatLook.Messages.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook.Theme`

- `childSerializers(): KSerializer[]`
- `constructor()`
- `deserialize(Decoder): ChatLook.Theme`
- `deserialize(Decoder): Object`
- `getAccent(): PingFrontColor`
- `getBackground(): PingFrontColor`
- `getCorners(): Corners`
- `getDensity(): Density`
- `getDescriptor(): SerialDescriptor`
- `getFont(): FontStyle`
- `getScheme(): ColorScheme`
- `getTeamBubble(): PingFrontColor`
- `serialize(Encoder, ChatLook.Theme): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChatLook.Theme.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook.Topic`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): ChatLook.Topic`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getLabel(): LocalizedText`
- `getPrefill(): LocalizedText`
- `getSymbol(): TopicSymbol`
- `serialize(Encoder, ChatLook.Topic): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChatLook.Topic.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChatLook.Welcome`

- `childSerializers(): KSerializer[]`
- `constructor()`
- `deserialize(Decoder): ChatLook.Welcome`
- `deserialize(Decoder): Object`
- `getBody(): LocalizedText`
- `getDescriptor(): SerialDescriptor`
- `getShowFaces(): Boolean`
- `getTitle(): LocalizedText`
- `serialize(Encoder, ChatLook.Welcome): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChatLook.Welcome.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ChoiceOption`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): ChoiceOption`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getLabel(): String`
- `serialize(Encoder, ChoiceOption): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.ChoiceOption.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ColorScheme`

- `getEntries(): EnumEntries`
- `valueOf(String): ColorScheme`
- `values(): ColorScheme[]`

### `dev.pingfront.model.ColorScheme.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Corners`

- `getEntries(): EnumEntries`
- `valueOf(String): Corners`
- `values(): Corners[]`

### `dev.pingfront.model.Corners.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Density`

- `getEntries(): EnumEntries`
- `valueOf(String): Density`
- `values(): Density[]`

### `dev.pingfront.model.Density.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Entitlement`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Entitlement`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getEnvironment(): String`
- `getExpiresAt(): Instant`
- `getGracePeriodEndsAt(): Instant`
- `getProductId(): String`
- `getRenewsAt(): Instant`
- `getSource(): String`
- `getState(): Entitlement.State`
- `isTestPurchase(): Boolean`
- `serialize(Encoder, Entitlement): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Entitlement.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Entitlement.Source`

- `getCode(): String`

### `dev.pingfront.model.Entitlement.Source.Companion`

- `getAdapty(): String`
- `getApp(): String`
- `getPlay(): String`
- `getRevenueCat(): String`
- `getStoreKit(): String`
- `getStripe(): String`
- `getSuperwall(): String`
- `serializer(): KSerializer`

### `dev.pingfront.model.Entitlement.State`

- `getEntries(): EnumEntries`
- `valueOf(String): Entitlement.State`
- `values(): Entitlement.State[]`

### `dev.pingfront.model.Entitlement.State.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.FontStyle`

- `getEntries(): EnumEntries`
- `valueOf(String): FontStyle`
- `values(): FontStyle[]`

### `dev.pingfront.model.FontStyle.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.HeaderStyle`

- `getEntries(): EnumEntries`
- `valueOf(String): HeaderStyle`
- `values(): HeaderStyle[]`

### `dev.pingfront.model.HeaderStyle.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.IdentityProblem`

- `getEntries(): EnumEntries`
- `valueOf(String): IdentityProblem`
- `values(): IdentityProblem[]`

### `dev.pingfront.model.IdentityProblem.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.LinkScreen`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): LinkScreen`
- `deserialize(Decoder): Object`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getParams(): Map`
- `serialize(Encoder, LinkScreen): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.LocalizedText`

- `constructor(Map)`
- `getValues(): Map`
- `resolve(String): String`

### `dev.pingfront.model.LocalizedText.Companion`

- `of(String): LocalizedText`
- `serializer(): KSerializer`

### `dev.pingfront.model.LookKt`

- `PingFrontColor(Long, Long): PingFrontColor`
- `PingFrontColor(String, String): PingFrontColor`

### `dev.pingfront.model.Message`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Message`
- `deserialize(Decoder): Object`
- `getAuthor(): Author`
- `getClientId(): String`
- `getCreatedAt(): Instant`
- `getDescriptor(): SerialDescriptor`
- `getFallback(): String`
- `getId(): String`
- `getParts(): List`
- `getPing(): PingOrigin`
- `getPlainText(): String`
- `getSeq(): Int`
- `getTranslation(): Translation`
- `isFromPerson(): Boolean`
- `serialize(Encoder, Message): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.Message.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.MessagePart`


### `dev.pingfront.model.MessagePart.CardPart`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): MessagePart.CardPart`
- `deserialize(Decoder): Object`
- `getCard(): Card`
- `getDescriptor(): SerialDescriptor`
- `getResult(): CardResult`
- `serialize(Encoder, MessagePart.CardPart): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.MessagePart.CardPart.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.MessagePart.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.MessagePart.Image`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): MessagePart.Image`
- `deserialize(Decoder): Object`
- `getAttachmentId(): String`
- `getDescriptor(): SerialDescriptor`
- `getHeight(): Int`
- `getRemoved(): Boolean`
- `getUrl(): String`
- `getWidth(): Int`
- `serialize(Encoder, MessagePart.Image): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.MessagePart.Image.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.MessagePart.Receipt`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): MessagePart.Receipt`
- `deserialize(Decoder): Object`
- `getAction(): ReceiptAction`
- `getDescriptor(): SerialDescriptor`
- `getText(): String`
- `serialize(Encoder, MessagePart.Receipt): Unit`
- `serialize(Encoder, Object): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.MessagePart.Receipt.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.MessagePart.Text`

- `getText(): String`

### `dev.pingfront.model.MessagePart.Unknown`

- `getType(): String`

### `dev.pingfront.model.OfferSlot`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): OfferSlot`
- `getAvailable(): Boolean`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getKind(): OfferSlot.Kind`
- `getProducts(): List`
- `getSource(): String`
- `getTitle(): String`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, OfferSlot): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.OfferSlot.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.OfferSlot.Kind`

- `getEntries(): EnumEntries`
- `valueOf(String): OfferSlot.Kind`
- `values(): OfferSlot.Kind[]`

### `dev.pingfront.model.OfferSlot.Kind.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.OfferSlot.Product`

- `childSerializers(): KSerializer[]`
- `constructor(String, String, String, String)`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): OfferSlot.Product`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getPeriod(): String`
- `getPrice(): String`
- `getTrial(): String`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, OfferSlot.Product): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.OfferSlot.Product.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.OfferSlot.Source`

- `getCode(): String`

### `dev.pingfront.model.OfferSlot.Source.Companion`

- `getAdapty(): String`
- `getApp(): String`
- `getPlay(): String`
- `getRevenueCat(): String`
- `getStoreKit(): String`
- `getSuperwall(): String`

### `dev.pingfront.model.PersonSummary`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): PersonSummary`
- `getDescriptor(): SerialDescriptor`
- `getEntitlement(): Entitlement`
- `getId(): String`
- `getLevel(): PersonSummary.Level`
- `getName(): String`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, PersonSummary): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.PersonSummary.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.PersonSummary.Level`

- `getEntries(): EnumEntries`
- `valueOf(String): PersonSummary.Level`
- `values(): PersonSummary.Level[]`

### `dev.pingfront.model.PersonSummary.Level.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.PingFrontColor`

- `childSerializers(): KSerializer[]`
- `constructor(RgbColor, RgbColor)`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): PingFrontColor`
- `getDark(): RgbColor`
- `getDescriptor(): SerialDescriptor`
- `getLight(): RgbColor`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, PingFrontColor): Unit`
- `typeParametersSerializers(): KSerializer[]`
- `value(Boolean): RgbColor`

### `dev.pingfront.model.PingFrontColor.Companion`

- `getBlue(): PingFrontColor`
- `getGreen(): PingFrontColor`
- `getOrange(): PingFrontColor`
- `getPink(): PingFrontColor`
- `getPurple(): PingFrontColor`
- `getTeal(): PingFrontColor`
- `serializer(): KSerializer`

### `dev.pingfront.model.PingOrigin`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): PingOrigin`
- `getDescriptor(): SerialDescriptor`
- `getExplanation(): String`
- `getKind(): PingOrigin.Kind`
- `getPlayId(): String`
- `getReason(): PingReason`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, PingOrigin): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.PingOrigin.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.PingOrigin.Kind`

- `getEntries(): EnumEntries`
- `valueOf(String): PingOrigin.Kind`
- `values(): PingOrigin.Kind[]`

### `dev.pingfront.model.PingOrigin.Kind.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.PingReason`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): PingReason`
- `getCode(): PingReason.Code`
- `getDescriptor(): SerialDescriptor`
- `getParams(): Map`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, PingReason): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.PingReason.Code`

- `getEntries(): EnumEntries`
- `valueOf(String): PingReason.Code`
- `values(): PingReason.Code[]`

### `dev.pingfront.model.PingReason.Code.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.PingReason.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ReceiptAction`

- `getEntries(): EnumEntries`
- `valueOf(String): ReceiptAction`
- `values(): ReceiptAction[]`

### `dev.pingfront.model.ReceiptAction.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ReviewMode`

- `getEntries(): EnumEntries`
- `valueOf(String): ReviewMode`
- `values(): ReviewMode[]`

### `dev.pingfront.model.ReviewMode.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.RgbColor`

- `constructor(Double, Double, Double)`
- `getArgb(): Int`
- `getBlue(): Double`
- `getGreen(): Double`
- `getHex(): String`
- `getLuminance(): Double`
- `getRed(): Double`
- `mixed(RgbColor, Double): RgbColor`

### `dev.pingfront.model.RgbColor.Companion`

- `argb(Int): RgbColor`
- `contrast(RgbColor, RgbColor): Double`
- `getBlack(): RgbColor`
- `getWhite(): RgbColor`
- `hex(String): RgbColor`
- `serializer(): KSerializer`

### `dev.pingfront.model.SendPolicy`

- `getCanSend(): Boolean`

### `dev.pingfront.model.SendPolicy.Closed`

- `getCanSend(): Boolean`
- `getReason(): String`

### `dev.pingfront.model.SendPolicy.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.SendPolicy.DefaultImpls`

- `getCanSend(SendPolicy): Boolean`

### `dev.pingfront.model.SendPolicy.Open`

- `getCanSend(): Boolean`

### `dev.pingfront.model.SendPolicy.Reason`

- `getCode(): String`

### `dev.pingfront.model.SendPolicy.Reason.Companion`

- `getAppPaused(): String`
- `getBlocked(): String`
- `getVerificationRequired(): String`

### `dev.pingfront.model.StoreOffer`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): StoreOffer`
- `getBasePlanId(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getKind(): StoreOffer.Kind`
- `getOfferId(): String`
- `getProductId(): String`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, StoreOffer): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.StoreOffer.Kind`

- `getEntries(): EnumEntries`
- `valueOf(String): StoreOffer.Kind`
- `values(): StoreOffer.Kind[]`

### `dev.pingfront.model.TeamMember`

- `childSerializers(): KSerializer[]`
- `deserialize(Decoder): Object`
- `deserialize(Decoder): TeamMember`
- `getAvatarUrl(): String`
- `getDescriptor(): SerialDescriptor`
- `getId(): String`
- `getName(): String`
- `getRole(): String`
- `getTimeZone(): String`
- `serialize(Encoder, Object): Unit`
- `serialize(Encoder, TeamMember): Unit`
- `typeParametersSerializers(): KSerializer[]`

### `dev.pingfront.model.TeamMember.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.ThreadStatus`

- `getEntries(): EnumEntries`
- `valueOf(String): ThreadStatus`
- `values(): ThreadStatus[]`

### `dev.pingfront.model.ThreadStatus.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Timestamps`

- `getEntries(): EnumEntries`
- `valueOf(String): Timestamps`
- `values(): Timestamps[]`

### `dev.pingfront.model.Timestamps.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.TopicSymbol`

- `getEntries(): EnumEntries`
- `valueOf(String): TopicSymbol`
- `values(): TopicSymbol[]`

### `dev.pingfront.model.TopicSymbol.Companion`

- `serializer(): KSerializer`

### `dev.pingfront.model.Translation`

- `getFrom(): String`
- `getLanguage(): String`
- `getTexts(): List`

### `dev.pingfront.model.Translation.Companion`

- `serializer(): KSerializer`

## `pingfront-ui`

### `dev.pingfront.ui.ChatUi`

- `install(ModuleContext): Unit`

### `dev.pingfront.ui.PingFrontChatActivity`

- `constructor()`
- `finish(): Unit`

### `dev.pingfront.ui.PingFrontChatDefaults`

- `getWindowInsets(Composer, Int): WindowInsets`

### `dev.pingfront.ui.PingFrontChatKt`

- `PingFrontChat(Modifier, ChatTopic, Function0, WindowInsets, Composer, Int, Int): Unit`
- `PingFrontChatSheet(Boolean, Function0, ChatTopic, Composer, Int, Int): Unit`
- `PingFrontChatSheet(PingFrontChatState, Composer, Int): Unit`
- `rememberPingFrontChatState(Composer, Int): PingFrontChatState`

### `dev.pingfront.ui.PingFrontChatState`

- `close(): Unit`
- `getTopic(): ChatTopic`
- `isVisible(): Boolean`
- `open(ChatTopic): Unit`

### `dev.pingfront.ui.PingFrontChatView`

- `constructor(Context)`
- `constructor(Context, AttributeSet)`
- `constructor(Context, AttributeSet, Int)`
- `disposeComposition(): Unit`
- `getActivityResultRegistryOwner(): ActivityResultRegistryOwner`
- `getInsets(): PingFrontChatView.Insets`
- `getOnClose(): Function0`
- `getTopic(): ChatTopic`
- `onVisibilityAggregated(Boolean): Unit`
- `setActivityResultRegistryOwner(ActivityResultRegistryOwner): Unit`
- `setId(Int): Unit`
- `setInsets(PingFrontChatView.Insets): Unit`
- `setOnClose(Function0): Unit`
- `setTopic(ChatTopic): Unit`
- `setViewCompositionStrategy(ViewCompositionStrategy): Unit`

### `dev.pingfront.ui.PingFrontChatView.Insets`


### `dev.pingfront.ui.PingFrontChatView.Insets.None`


### `dev.pingfront.ui.PingFrontChatView.Insets.Padding`

- `constructor(Int, Int, Int, Int)`
- `getBottom(): Int`
- `getLeft(): Int`
- `getRight(): Int`
- `getTop(): Int`

### `dev.pingfront.ui.PingFrontChatView.Insets.SafeDrawing`


### `dev.pingfront.ui.ThemeKt`

- `PingFrontColor--OWjLjI(Long, Long): PingFrontColor`
- `toPingFrontColor(Long): PingFrontColor`

## `pingfront-push`

### `dev.pingfront.push.PingFrontMessagingService`

- `constructor()`
- `onMessageReceived(RemoteMessage): Unit`
- `onRegistered(String): Unit`

### `dev.pingfront.push.PingFrontPush`

- `contentIntent(Context, Map): PendingIntent`
- `handleMessage(Context, Map): Boolean`
- `isPingFrontMessage(Map): Boolean`

### `dev.pingfront.push.Push`

- `install(ModuleContext): Unit`

## `pingfront-billing`

### `dev.pingfront.billing.Billing`

- `install(ModuleContext): Unit`
- `invoke(List): PingFrontModule`
- `invoke(List, Function2): PingFrontModule`

## `pingfront-reports`

### `dev.pingfront.reports.ReportActivity`

- `constructor()`

### `dev.pingfront.reports.Reports`

- `install(ModuleContext): Unit`
- `invoke(Boolean, Boolean): PingFrontModule`

## `pingfront`

### `dev.pingfront.sdk.PingFrontSdk`


## Adapters

Vendor adapters, one optional artifact each (see README.md, Adapters). Each reports offer slots with its
`source`, opens the vendor's paywall for offer cards, keeps the entitlement current and links identity.

## `pingfront-revenuecat`

### `dev.pingfront.revenuecat.RevenueCat`

- `install(ModuleContext): Unit`

## `pingfront-superwall`

### `dev.pingfront.superwall.Superwall`

- `invoke(SuperwallPlacement[]): PingFrontModule`

### `dev.pingfront.superwall.SuperwallPlacement`

- `constructor(String, String, OfferSlot.Kind)`
- `getKind(): OfferSlot.Kind`
- `getPlacement(): String`
- `getTitle(): String`

## `pingfront-adapty`

### `dev.pingfront.adapty.Adapty`

- `invoke(String[]): PingFrontModule`
