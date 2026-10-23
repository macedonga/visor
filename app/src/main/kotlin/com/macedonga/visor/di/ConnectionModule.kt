package com.macedonga.visor.di

import android.content.Context
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.connection.AndroidTransportFactory
import com.macedonga.visor.connection.LinkKind
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.LiveAiCamera
import com.macedonga.visor.core.assistant.GlassesPhotos
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.SVC_HEARING
import com.macedonga.visor.core.assistant.SVC_TAR
import com.macedonga.visor.core.assistant.Timers
import com.macedonga.visor.core.assistant.SVC_GLASSES_PHOTO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.Brain
import com.macedonga.visor.core.assistant.Diary
import com.macedonga.visor.core.assistant.SVC_ASSISTANT
import com.macedonga.visor.core.assistant.Voice
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.assistant.mcp.McpStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesWifi
import com.macedonga.visor.core.connection.RealSessionHostFactory
import com.macedonga.visor.core.connection.SessionHostFactory
import com.macedonga.visor.core.connection.Shutter
import com.macedonga.visor.core.connection.TransportFactory
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.KeystoreSealer
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.Screencast
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.LiveOutputs
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.SVC_AIRSHIP
import com.macedonga.visor.core.media.SVC_MEDIA_STREAMING_CONTROL
import com.macedonga.visor.core.media.StreamReceiver
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.RlDriveResponder
import com.macedonga.visor.core.settings.SVC_RLDRIVE
import com.macedonga.visor.core.transport.FileWireLog
import com.macedonga.visor.core.transport.LinkMeter
import com.macedonga.visor.notify.GlassesNotification
import com.macedonga.visor.notify.NotifyIcons
import com.macedonga.visor.phone.PhoneActions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import com.macedonga.visor.core.protocol.ServiceId
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Where the connection is assembled. */
@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    /** The sealed store, in the app's own files directory. */
    @Provides
    @Singleton
    fun identityStore(@ApplicationContext context: Context): IdentityStore =
        IdentityStore(File(context.filesDir, "identity"), KeystoreSealer())

    /**
     * Debug builds append every SDU to `files/wire.log`, in the desktop's format, so a phone run
     * can be diffed against a desktop run.
     */
    @Provides
    @Singleton
    fun transportFactory(@ApplicationContext context: Context, meter: LinkMeter, kind: LinkKind, keeper: LeKeeper): TransportFactory {
        if (BuildConfig.DEBUG) DataXConnection.trace = dataxTrace(File(context.filesDir, "datax.log"))
        return AndroidTransportFactory(
            context,
            wireLog = if (BuildConfig.DEBUG) FileWireLog(File(context.filesDir, "wire.log")) else null,
            wifiMeter = meter,
            kind = kind,
            keeper = keeper,
        )
    }

    // Starts over past 32 MB; flushed at most four times a second, like the wire log.
    private fun dataxTrace(file: File): (String) -> Unit {
        val out = java.io.PrintWriter(java.io.BufferedWriter(java.io.FileWriter(file, file.length() < 32L shl 20), 1 shl 16))
        var flushedAt = 0L
        return { lines ->
            synchronized(out) {
                val now = System.currentTimeMillis()
                out.append(java.time.LocalTime.now().toString()).append(' ').append(lines).append('\n')
                if (now - flushedAt >= 250) {
                    out.flush()
                    flushedAt = now
                }
            }
        }
    }

    @Provides
    @Singleton
    fun leKeeper(@ApplicationContext context: Context): LeKeeper = LeKeeper(context)

    @Provides
    @Singleton
    fun linkKind(): LinkKind = LinkKind()

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
        hearing: javax.inject.Provider<Hearing>,
        assistant: javax.inject.Provider<Assistant>,
        lease: javax.inject.Provider<com.macedonga.visor.band.DeviceLease>,
        icons: NotifyIcons,
        internet: com.macedonga.visor.connection.InternetSwitch,
        fetcher: com.macedonga.visor.connection.DataFetcher,
        tunnel: com.macedonga.visor.connection.PhoneInternet,
        panels: com.macedonga.visor.connection.Panels,
        bloks: com.macedonga.visor.connection.BloksRelay,
        location: com.macedonga.visor.connection.LocationShare,
        messages: com.macedonga.visor.connection.PhoneMessages,
        sharing: com.macedonga.visor.connection.PhoneSharing,
        calls: com.macedonga.visor.connection.PhoneCalls,
        media: com.macedonga.visor.connection.PhoneMedia,
        captureState: com.macedonga.visor.connection.CaptureState,
        leases: com.macedonga.visor.connection.LeaseService,
    ): SessionHostFactory = RealSessionHostFactory(
        transports,
        responders = mapOf(
            SVC_AIRSHIP to airship,
            SVC_RLDRIVE to drive,
            SVC_MEDIA_STREAMING_CONTROL to stream,
            SVC_ASSISTANT to Responder { message, mux -> assistant.get().respond(message, mux) },
            SVC_GLASSES_PHOTO to photos,
            SVC_TAR to timers,
            GlassesNotification.SVC to icons,
            com.macedonga.visor.connection.InternetSwitch.SVC to internet,
            com.macedonga.visor.connection.InternetSwitch.PING_SVC to internet.ping,
            com.macedonga.visor.connection.MotionActivity.SVC to com.macedonga.visor.connection.MotionActivity,
            com.macedonga.visor.connection.DataFetcher.SVC to fetcher,
            com.macedonga.visor.connection.PhoneInternet.SVC to tunnel,
            com.macedonga.visor.connection.Panels.SVC to panels,
            com.macedonga.visor.connection.BloksRelay.SVC to bloks,
            com.macedonga.visor.connection.LocationShare.SVC to location,
            com.macedonga.visor.connection.PhoneMessages.SVC to messages,
            com.macedonga.visor.connection.PhoneSharing.SVC to sharing,
            com.macedonga.visor.connection.PhoneCalls.SVC to calls,
            com.macedonga.visor.connection.PhoneMedia.SVC to media,
            com.macedonga.visor.connection.CaptureState.SVC to captureState,
            com.macedonga.visor.connection.LeaseService.SVC to leases,
            SVC_HEARING to Responder { message, mux -> hearing.get().respond(message, mux) },
            ServiceId.CONN_DEVICE_LEASE.value to Responder { message, mux -> lease.get().respond(message, mux) },
        ),
    )

    @Provides
    @Singleton
    fun timers(@ApplicationContext context: Context): Timers = Timers(File(context.filesDir, "timers.json"))

    @Provides
    @Singleton
    fun hearing(connection: GlassesConnection): Hearing = Hearing(connection)

    @Provides
    @Singleton
    fun teleprompter(connection: GlassesConnection): com.macedonga.visor.core.connection.Teleprompter =
        com.macedonga.visor.core.connection.Teleprompter(connection)

    @Provides
    @Singleton
    fun captions(connection: GlassesConnection): com.macedonga.visor.core.connection.Captions =
        com.macedonga.visor.core.connection.Captions(connection)

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
    ): Assistant = Assistant(
        context, connection, Voice(context), Brain({ config.config.value }, diary, hub, config, phone), config, airship, diary,
        live.wifiSession, live::frameNow,
        object : LiveAiCamera {
            // A Live view already running is the camera; only a stream Live AI opened is Live AI's to close.
            override fun on() = live.startLiveAi()
            override fun off() {
                if (live.forLiveAi) live.stop()
            }
            override val frames = live.status.map { it.phase == com.macedonga.visor.core.media.LivePhase.RUNNING }.distinctUntilChanged()
        },
        photos,
        { settings.value("assistant_voice_id") },
        { com.macedonga.visor.core.settings.asBool(settings.value("detailed_responses_live_ai_enabled")) },
        hearing,
        { (glassesConfig.state.value.values[com.macedonga.visor.language.PROCESSING_TIMEOUT] as? Number)?.toLong() },
        phone,
        { com.macedonga.visor.settings.hasDisplay(connection.state.value.info?.build) },
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
    ): LiveStream = LiveStream(context, connection, receiver, store, hosts, meter, context.filesDir, outputs)

    @Provides
    @Singleton
    fun liveOutputs(@ApplicationContext context: Context): LiveOutputs = LiveOutputs(context)

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
    ): MediaImport = MediaImport(context, connection, airship, store, hosts)

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
    fun screencast(connection: GlassesConnection, config: GlassesConfig, live: com.macedonga.visor.core.media.LiveStream): Screencast =
        Screencast(connection, config, live)

    @Provides
    @Singleton
    fun glassesSettings(connection: GlassesConnection, drive: RlDriveResponder): GlassesSettings =
        GlassesSettings(connection, drive)

    @Provides
    @Singleton
    fun glassesWifi(connection: GlassesConnection): GlassesWifi = GlassesWifi(connection)
}
