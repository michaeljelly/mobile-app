package io.rebble.cobble.bridges.common

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import io.rebble.cobble.bridges.FlutterBridge
import io.rebble.cobble.bridges.background.BackgroundAppInstallBridge
import io.rebble.cobble.bridges.ui.BridgeLifecycleController

import io.rebble.cobble.data.pbw.appinfo.toPigeon
import io.rebble.cobble.datasources.WatchMetadataStore
import io.rebble.cobble.middleware.PutBytesController
import io.rebble.cobble.middleware.getBestVariant
import io.rebble.cobble.pigeons.BooleanWrapper
import io.rebble.cobble.pigeons.NumberWrapper
import io.rebble.cobble.pigeons.Pigeons
import io.rebble.cobble.util.*
import io.rebble.libpebblecommon.disk.PbwBinHeader
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.packets.AppOrderResultCode
import io.rebble.libpebblecommon.packets.AppReorderRequest
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.AppReorderService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okio.buffer
import okio.sink
import okio.source
import timber.log.Timber
import java.io.InputStream
import java.util.*
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
class AppInstallFlutterBridge @Inject constructor(
        private val context: Context,
        private val coroutineScope: CoroutineScope,
        private val backgroundAppInstallBridge: BackgroundAppInstallBridge,
        private val watchMetadataStore: WatchMetadataStore,
        private val blobDBService: BlobDBService,
        private val reorderService: AppReorderService,
        private val putBytesController: PutBytesController,
        bridgeLifecycleController: BridgeLifecycleController
) : FlutterBridge, Pigeons.AppInstallControl {
    private val statusCallbacks = bridgeLifecycleController.createCallbacks(
            Pigeons::AppInstallStatusCallbacks
    )

    private var statusObservingJob: Job? = null

    companion object {
        private val json = Json { ignoreUnknownKeys = true}
    }

    init {
        bridgeLifecycleController.setupControl(Pigeons.AppInstallControl::setup, this)
    }

    @Suppress("IfThenToElvis")
    override fun getAppInfo(
            arg: Pigeons.StringWrapper,
            result: Pigeons.Result<Pigeons.PbwAppInfo>) {
        coroutineScope.launchPigeonResult(result) {
            val uri: String = arg.value!!


            val parsingResult = parsePbwFileMetadata(uri)
            if (parsingResult != null) {
                parsingResult.toPigeon()
            } else {
                Pigeons.PbwAppInfo().apply { isValid = false; watchapp = Pigeons.WatchappInfo() }
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun parsePbwFileMetadata(
            uri: String
    ): PbwAppInfo? = withContext(Dispatchers.IO) {
        try {
            val stream = openUriStream(uri)

            ZipInputStream(stream).use { zipInputStream ->
                zipInputStream.findFile("appinfo.json") ?: return@use null
                parseAppInfoJson(zipInputStream)
            }
        } catch (e: Exception) {
            Timber.e(e, "App parsing failed")
            null
        }
    }


    override fun beginAppInstall(
            installData: Pigeons.InstallData,
            result: Pigeons.Result<Pigeons.BooleanWrapper>
    ) {
        coroutineScope.launchPigeonResult(result) {
            // Copy pbw file to the app's folder
            val appUuid = installData.appInfo.uuid!!
            val targetFileName = getAppPbwFile(context, appUuid)

            val success = if (!installData.stayOffloaded) {
                withContext(Dispatchers.IO) {
                    val openInputStream = openUriStream(installData.uri)

                    if (openInputStream == null) {
                        Timber.e("Unknown URI '%s'. This should have been filtered before it reached beginAppInstall. Aborting.", installData.uri)
                        return@withContext false
                    }

                    val source = openInputStream
                            .source()
                            .buffer()

                    val sink = targetFileName.sink().buffer()

                    source.use {
                        sink.use {
                            sink.writeAll(source)
                        }
                    }

                    true
                }
            } else {
                true
            }

            if (success && !installData.stayOffloaded) {
                backgroundAppInstallBridge.installAppNow(installData.uri, installData.appInfo)
            }

            BooleanWrapper(true)
        }
    }

    override fun insertAppIntoBlobDb(arg: Pigeons.StringWrapper, result: Pigeons.Result<Pigeons.NumberWrapper>) {
        coroutineScope.launchPigeonResult(result, Dispatchers.IO) {
            NumberWrapper(try {
                val appUuid = arg.value!!

                val appFile = getAppPbwFile(context, appUuid)
                if (!appFile.exists()) {
                    error("PBW file ${appFile.absolutePath} missing")
                }
                Timber.d("Len: ${appFile.length()}")


                // Wait some time for metadata to become available in case this has been called
                // Right after watch has been connected
                val hardwarePlatformNumber = withTimeoutOrNull(2_000) {
                    watchMetadataStore.lastConnectedWatchMetadata.first { it != null }
                }
                        ?.running
                        ?.hardwarePlatform
                        ?.get()
                        ?: error("Watch not connected")

                val connectedWatchType = WatchHardwarePlatform
                        .fromProtocolNumber(hardwarePlatformNumber)
                        ?.watchType
                        ?: error("Unknown hardware platform $hardwarePlatformNumber")

                val appInfo = requirePbwAppInfo(appFile)

                val targetWatchType = getBestVariant(connectedWatchType, appInfo.targetPlatforms)
                        ?: error("Watch $connectedWatchType is not compatible with app $appUuid. " +
                                "Compatible apps: ${appInfo.targetPlatforms}")


                val manifest = requirePbwManifest(appFile, targetWatchType)

                Timber.d("Manifest %s", manifest)

                val appBlob = requirePbwBinaryBlob(appFile, targetWatchType, manifest.application.name)

                val headerData = appBlob
                        .buffer().use { it.readByteArray(PbwBinHeader.SIZE.toLong()) }

                val parsedHeader = PbwBinHeader.parseFileHeader(headerData.toUByteArray())

                val insertResult = blobDBService.send(
                        BlobCommand.InsertCommand(
                                Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort(),
                                BlobCommand.BlobDatabase.App,
                                parsedHeader.uuid.toBytes(),
                                parsedHeader.toBlobDbApp().toBytes()
                        )
                )

                insertResult.responseValue.value.toInt()
            } catch (e: Exception) {
                Timber.e(e, "Failed to send PBW file to the BlobDB")
                BlobResponse.BlobStatus.GeneralFailure.value.toInt()
            })
        }
    }

    override fun beginAppDeletion(arg: Pigeons.StringWrapper,
                                  result: Pigeons.Result<Pigeons.BooleanWrapper>) {
        coroutineScope.launchPigeonResult(result) {
            getAppPbwFile(context, arg.value!!).delete()

            BooleanWrapper(backgroundAppInstallBridge.deleteApp(arg))
        }
    }

    override fun removeAppFromBlobDb(
            arg: Pigeons.StringWrapper,
            result: Pigeons.Result<Pigeons.NumberWrapper>
    ) {
        coroutineScope.launchPigeonResult(result) {
            val blobDbResult = blobDBService.send(
                    BlobCommand.DeleteCommand(
                            Random.nextInt().toUShort(),
                            BlobCommand.BlobDatabase.App,
                            SUUID(StructMapper(), UUID.fromString(arg.value)).toBytes()
                    )
            )

            NumberWrapper(blobDbResult.response.valueNumber)
        }
    }

    override fun sendAppOrderToWatch(
            arg: Pigeons.ListWrapper,
            result: Pigeons.Result<Pigeons.NumberWrapper>
    ) {
        coroutineScope.launchPigeonResult(result) {
            val uuids = arg.value!!.map { UUID.fromString(it!! as String) }
            reorderService.send(
                    AppReorderRequest(uuids)
            )

            val resultPacket = withTimeoutOrNull(10_000) {
                reorderService.receivedMessages.receive()
            }

            if (resultPacket?.status?.get() == AppOrderResultCode.SUCCESS.value) {
                NumberWrapper(BlobResponse.BlobStatus.Success.value.toInt())
            } else {
                NumberWrapper(BlobResponse.BlobStatus.WatchDisconnected.value.toInt())
            }
        }

    }

    override fun removeAllApps(result: Pigeons.Result<Pigeons.NumberWrapper>) {
        coroutineScope.launchPigeonResult(result) {
            NumberWrapper(
                    blobDBService.send(BlobCommand.ClearCommand(
                            Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort(),
                            BlobCommand.BlobDatabase.App
                    )).response.get().toInt()
            )
        }
    }

    override fun subscribeToAppStatus() {
        statusObservingJob = coroutineScope.launch {
            putBytesController.status.collect {
                val statusPigeon = Pigeons.AppInstallStatus.Builder()
                        .setIsInstalling(it.state == PutBytesController.State.SENDING)
                        .setProgress(it.progress)
                        .build()

                statusCallbacks.onStatusUpdated(statusPigeon) {}
            }
        }
    }

    override fun unsubscribeFromAppStatus() {
        statusObservingJob?.cancel()
    }

    private fun openUriStream(uri: String): InputStream? {
        val parsedUri = Uri.parse(uri)

        return when (parsedUri.scheme) {
            "content" -> context.contentResolver.openInputStream(parsedUri)
            "file" -> parsedUri.toFile().inputStream().buffered()
            else -> {
                Timber.e("Unknown uri type: %s", uri)
                null
            }
        }
    }

    private fun parseAppInfoJson(stream: InputStream): PbwAppInfo? {
        return json.decodeFromStream(stream)
    }
}
