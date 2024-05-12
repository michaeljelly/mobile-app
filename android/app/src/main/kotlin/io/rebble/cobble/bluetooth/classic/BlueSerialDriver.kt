package io.rebble.cobble.bluetooth.classic

import android.bluetooth.BluetoothDevice
import io.rebble.cobble.bluetooth.BlueIO
import io.rebble.cobble.bluetooth.PebbleBluetoothDevice
import io.rebble.cobble.bluetooth.ProtocolIO
import io.rebble.cobble.bluetooth.SingleConnectionStatus
import io.rebble.cobble.datasources.IncomingPacketsListener
import io.rebble.libpebblecommon.ProtocolHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.*

@Suppress("BlockingMethodInNonBlockingContext")
class BlueSerialDriver(
        private val protocolHandler: ProtocolHandler,
        private val incomingPacketsListener: IncomingPacketsListener
) : BlueIO {
    private var protocolIO: ProtocolIO? = null

    @FlowPreview
    override fun startSingleWatchConnection(device: PebbleBluetoothDevice): Flow<SingleConnectionStatus> = flow {
        require(!device.emulated)
        require(device.bluetoothDevice != null)
        coroutineScope {
            emit(SingleConnectionStatus.Connecting(device))

            val btSerialUUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
            val serialSocket = withContext(Dispatchers.IO) {
                device.bluetoothDevice.createRfcommSocketToServiceRecord(btSerialUUID).also {
                    it.connect()
                }
            }

            val sendLoop = launch {
                protocolHandler.startPacketSendingLoop(::sendPacket)
            }

            emit(SingleConnectionStatus.Connected(device))

            protocolIO = ProtocolIO(
                    serialSocket.inputStream,
                    serialSocket.outputStream,
                    protocolHandler,
                    incomingPacketsListener
            )

            protocolIO!!.readLoop()
            try {
                serialSocket?.close()
            } catch (e: IOException) {
            }
            sendLoop.cancel()
        }
    }

    private suspend fun sendPacket(bytes: UByteArray): Boolean {
        val protocolIO = protocolIO ?: return false
        @Suppress("BlockingMethodInNonBlockingContext")
        protocolIO.write(bytes.toByteArray())
        return true
    }

}