package com.macedonga.visor.di

import android.content.Context
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.band.DeviceLease
import com.macedonga.visor.connection.AndroidTransportFactory
import com.macedonga.visor.connection.BloksRelay
import com.macedonga.visor.connection.CaptureState
import com.macedonga.visor.connection.DataFetcher
import com.macedonga.visor.connection.InternetSwitch
import com.macedonga.visor.connection.LeaseService
import com.macedonga.visor.connection.LinkKind
import com.macedonga.visor.connection.LocationShare
import com.macedonga.visor.connection.MapsConstellation
import com.macedonga.visor.connection.MapsStore
import com.macedonga.visor.connection.MapsSupport
import com.macedonga.visor.connection.MotionActivity
import com.macedonga.visor.connection.Navigation
import com.macedonga.visor.connection.Panels
import com.macedonga.visor.connection.PeerAppInfo
import com.macedonga.visor.connection.PhoneCalls
import com.macedonga.visor.connection.PhoneInternet
import com.macedonga.visor.connection.PhoneMedia
import com.macedonga.visor.connection.PhoneMessages
import com.macedonga.visor.connection.PhoneSharing
import com.macedonga.visor.connection.PlaceSearch
import com.macedonga.visor.connection.WebApps
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.Brain
import com.macedonga.visor.core.assistant.Diary
import com.macedonga.visor.core.assistant.GlassesPhotos
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.SVC_ASSISTANT
import com.macedonga.visor.core.assistant.SVC_GLASSES_PHOTO
import com.macedonga.visor.core.assistant.SVC_HEARING
import com.macedonga.visor.core.assistant.SVC_TAR
import com.macedonga.visor.core.assistant.Timers
import com.macedonga.visor.core.assistant.Voice
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.assistant.mcp.McpStore
import com.macedonga.visor.core.connection.Captions
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesWifi
import com.macedonga.visor.core.connection.RealSessionHostFactory
import com.macedonga.visor.core.connection.SVC_PEER_BUILD_INFO
import com.macedonga.visor.core.connection.SessionHostFactory
import com.macedonga.visor.core.connection.Shutter
import com.macedonga.visor.core.connection.Teleprompter
import com.macedonga.visor.core.connection.TransportFactory
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.KeystoreSealer
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.LiveOutputs
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.Screencast
import com.macedonga.visor.core.media.StreamReceiver
import com.macedonga.visor.core.protocol.SVC_AIRSHIP
import com.macedonga.visor.core.protocol.SVC_MEDIA_STREAMING_CONTROL
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.RlDriveResponder
import com.macedonga.visor.core.settings.SVC_RLDRIVE
import com.macedonga.visor.core.settings.asBool
import com.macedonga.visor.core.transport.FileWireLog
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.core.transport.LinkMeter
import com.macedonga.visor.language.PROCESSING_TIMEOUT
import com.macedonga.visor.metaai.AssistantCamera
import com.macedonga.visor.notify.GlassesNotification
import com.macedonga.visor.notify.NotifyIcons
import com.macedonga.visor.phone.PhoneActions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton

/** Where the connection is assembled. */
@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    /** The sealed store, in the app's own files directory. */
    @Provides
    @Singleton
    fun identityStore(@ApplicationContext context: Context): IdentityStore =
        IdentityStore(File(context.filesDir, IdentityStore.DIRECTORY), KeystoreSealer())

    /**
     * Debug builds append every SDU to `files/wire.log`, in the desktop's format, so a phone run
     * can be diffed against a desktop run.
     */
    @Provides
    @Singleton
    fun transportFactory(@ApplicationContext context: Context, meter: LinkMeter, kind: LinkKind, keeper: LeKeeper): TransportFactory =
        AndroidTransportFactory(
            context,
            wireLog = if (BuildConfig.DEBUG) FileWireLog(File(context.filesDir, "wire.log")) else null,
            wifiMeter = meter,
            kind = kind,
            keeper = keeper,
        )

    @Provides
    @Singleton
    fun leKeeper(@ApplicationContext context: Context): LeKeeper = LeKeeper(context)

    @Provides
    @Singleton
    fun linkMeter(): LinkMeter = LinkMeter()

    @Provides
    @Singleton
    fun airship(@ApplicationContext context: Context): AirshipReceiver =
        AirshipReceiver(File(context.cacheDir, "airship"))

    @Provides
    @Singleton
    fun rlDrive(): RlDriveResponder = RlDriveResponder()

    @Provides
    @Singleton
    fun streamReceiver(): StreamReceiver = StreamReceiver()

    @Provides
    @Singleton
    fun sessionHostFactory(
        transports: TransportFactory,
        airship: AirshipReceiver,
        drive: RlDriveResponder,
        stream: StreamReceiver,
        photos: GlassesPhotos,
        timers: Timers,
        // A Provider, because these need the connection this factory feeds.
        hearing: Provider<Hearing>,
        assistant: Provider<Assistant>,
        lease: Provider<DeviceLease>,
        icons: NotifyIcons,
        internet: InternetSwitch,
        fetcher: DataFetcher,
        tunnel: PhoneInternet,
        panels: Panels,
        bloks: BloksRelay,
        location: LocationShare,
        messages: PhoneMessages,
        sharing: PhoneSharing,
        calls: PhoneCalls,
        media: PhoneMedia,
        captureState: CaptureState,
        leases: LeaseService,
        navigation: Navigation,
        placeSearch: PlaceSearch,
        mapsSupport: MapsSupport,
        mapsStore: MapsStore,
        mapsConstellation: MapsConstellation,
        webApps: WebApps,
        peerAppInfo: PeerAppInfo,
    ): SessionHostFactory = RealSessionHostFactory(
        transports,
        responders = mapOf(
            SVC_AIRSHIP to airship,
            SVC_RLDRIVE to drive,
            SVC_MEDIA_STREAMING_CONTROL to stream,
            SVC_ASSISTANT to later(assistant),
            SVC_GLASSES_PHOTO to photos,
            SVC_TAR to timers,
            GlassesNotification.SVC to icons,
            InternetSwitch.SVC to internet,
            InternetSwitch.PING_SVC to internet.ping,
            MotionActivity.SVC to MotionActivity,
            DataFetcher.SVC to fetcher,
            PhoneInternet.SVC to tunnel,
            Panels.SVC to panels,
            BloksRelay.SVC to bloks,
            LocationShare.SVC to location,
            PhoneMessages.SVC to messages,
            PhoneSharing.SVC to sharing,
            PhoneCalls.SVC to calls,
            PhoneMedia.SVC to media,
            CaptureState.SVC to captureState,
            LeaseService.SVC to leases,
            Navigation.SVC to navigation,
            PlaceSearch.SVC to placeSearch,
            MapsSupport.SVC to mapsSupport,
            MapsStore.SVC to mapsStore,
            MapsConstellation.SVC to mapsConstellation,
            WebApps.SVC to webApps,
            SVC_PEER_BUILD_INFO to peerAppInfo,
            SVC_HEARING to later(hearing),
            ServiceId.CONN_DEVICE_LEASE.value to later(lease),
        ),
    )

    /** [p]'s responder, fetched when a message comes; closes reach it too. */
    private fun later(p: Provider<out Responder>) = object : Responder {
        override suspend fun respond(message: DataXMessage, connection: DataXConnection) = p.get().respond(message, connection)
        override suspend fun closed(channelId: Int, connection: DataXConnection) = p.get().closed(channelId, connection)
    }

    @Provides
    @Singleton
    fun timers(@ApplicationContext context: Context): Timers = Timers(File(context.filesDir, "timers.json"))

    @Provides
    @Singleton
    fun hearing(connection: GlassesConnection): Hearing = Hearing(connection)

    @Provides
    @Singleton
    fun teleprompter(connection: GlassesConnection): Teleprompter = Teleprompter(connection)

    @Provides
    @Singleton
    fun captions(connection: GlassesConnection): Captions = Captions(connection)

    @Provides
    @Singleton
    fun shutter(connection: GlassesConnection): Shutter = Shutter(connection)

    @Provides
    @Singleton
    fun assistantConfig(@ApplicationContext context: Context): AssistantConfigStore = AssistantConfigStore(context)

    @Provides
    @Singleton
    fun mcpStore(@ApplicationContext context: Context): McpStore = McpStore(context)

    @Provides
    @Singleton
    fun mcpHub(@ApplicationContext context: Context, store: McpStore): McpHub = McpHub(context, store)

    @Provides
    @Singleton
    fun diary(@ApplicationContext context: Context): Diary =
        Diary(File(context.filesDir, "assistant"), File(context.filesDir, "assistant_turns.json"), File(context.cacheDir, "assistant"))

    @Provides
    @Singleton
    fun assistant(
        @ApplicationContext context: Context,
        connection: GlassesConnection,
        config: AssistantConfigStore,
        airship: AirshipReceiver,
        live: LiveStream,
        photos: GlassesPhotos,
        settings: GlassesSettings,
        diary: Diary,
        hub: McpHub,
        hearing: Hearing,
        glassesConfig: GlassesConfig,
        phone: PhoneActions,
        camera: AssistantCamera,
    ): Assistant = Assistant(
        context, connection, Voice(context), Brain(config, diary, hub, phone), config, airship, diary,
        liveSession = live.wifiSession,
        liveFrame = live::frameNow,
        camera = camera,
        glassesPhotos = photos,
        glassesVoice = { settings.value("assistant_voice_id") },
        liveAiDetailed = { asBool(settings.value("detailed_responses_live_ai_enabled")) },
        hearing = hearing,
        processingTimeoutS = { (glassesConfig.state.value.values[PROCESSING_TIMEOUT] as? Number)?.toLong() },
        phone = phone,
        display = { isDisplayBuild(connection.state.value.info?.build) },
        cameraAction = camera::act,
    )

    @Provides
    @Singleton
    fun glassesPhotos(): GlassesPhotos = GlassesPhotos()

    @Provides
    @Singleton
    fun liveStream(
        @ApplicationContext context: Context,
        connection: GlassesConnection,
        receiver: StreamReceiver,
        store: IdentityStore,
        hosts: SessionHostFactory,
        meter: LinkMeter,
        outputs: LiveOutputs,
    ): LiveStream = LiveStream(context, connection, receiver, store, hosts, meter, outputs)

    @Provides
    @Singleton
    fun liveOutputs(@ApplicationContext context: Context, albums: Albums): LiveOutputs = LiveOutputs(context, albums)

    @Provides
    @Singleton
    fun albums(@ApplicationContext context: Context): Albums = Albums(context)

    @Provides
    @Singleton
    fun liveSettings(@ApplicationContext context: Context): LiveSettingsStore = LiveSettingsStore(context)

    @Provides
    @Singleton
    fun glassesConnection(store: IdentityStore, hosts: SessionHostFactory): GlassesConnection =
        GlassesConnection(store, hosts)

    @Provides
    @Singleton
    fun mediaImport(
        @ApplicationContext context: Context,
        connection: GlassesConnection,
        airship: AirshipReceiver,
        store: IdentityStore,
        hosts: SessionHostFactory,
        albums: Albums,
    ): MediaImport = MediaImport(context, albums, connection, airship, store, hosts)

    @Provides
    @Singleton
    fun glassesConfig(
        @ApplicationContext context: Context,
        connection: GlassesConnection,
        airship: AirshipReceiver,
        media: MediaImport,
    ): GlassesConfig = GlassesConfig(context, connection, airship, media)

    @Provides
    @Singleton
    fun screencast(connection: GlassesConnection, config: GlassesConfig, live: LiveStream): Screencast =
        Screencast(connection, config, live)

    @Provides
    @Singleton
    fun glassesSettings(connection: GlassesConnection, drive: RlDriveResponder): GlassesSettings =
        GlassesSettings(connection, drive)

    @Provides
    @Singleton
    fun glassesWifi(connection: GlassesConnection): GlassesWifi = GlassesWifi(connection)
}
