/********************************************************************************
 * Copyright (c) 2020 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.keyple.coppernic.ask.plugin

import fr.coppernic.sdk.ask.Defines
import fr.coppernic.sdk.ask.RfidTag
import fr.coppernic.sdk.ask.sCARD_SearchExt
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.experimental.or
import org.eclipse.keyple.core.plugin.AbstractLocalReader
import org.eclipse.keyple.core.plugin.AbstractObservableLocalReader
import org.eclipse.keyple.core.plugin.DontWaitForCardRemovalDuringProcessing
import org.eclipse.keyple.core.plugin.WaitForCardInsertionBlocking
import org.eclipse.keyple.core.plugin.WaitForCardRemovalNonBlocking
import org.eclipse.keyple.core.service.event.ReaderObservationExceptionHandler
import org.eclipse.keyple.core.service.exception.KeypleReaderIOException
import org.eclipse.keyple.core.service.exception.KeypleReaderProtocolNotSupportedException
import org.eclipse.keyple.core.util.ByteArrayUtil
import timber.log.Timber

/**
 * Implementation of {@link org.eclipse.keyple.core.seproxy.SeReader} to communicate with NFC Tag
 * using ASK Coppernic library
 */
internal class Cone2ContactlessReaderImpl(private val readerObservationExceptionHandler: ReaderObservationExceptionHandler) : AbstractObservableLocalReader(
    Cone2Plugin.PLUGIN_NAME,
    Cone2ContactlessReader.READER_NAME
), Cone2ContactlessReader,
    WaitForCardInsertionBlocking,
    DontWaitForCardRemovalDuringProcessing,
    WaitForCardRemovalNonBlocking {

    // Map used to store the activated contactless communication protocols
    private val protocolsMap = mutableMapOf<String, Byte>()

    // Instance of the Paragon NFC reader
    private val reader = ParagonReader.getInstance()

    // RFID tag information returned when card is detected
    private var rfidTag: RfidTag? = null

    // Indicates whether or not physical channel is opened.
    // Physical channel is irrelevant for ASK reader
    private val isPhysicalChannelOpened = AtomicBoolean(false)

    // This boolean indicates that a card has been discovered
    private val isCardDiscovered = AtomicBoolean(false)
    private val isWaitingForCard = AtomicBoolean(false)

    private var mCheckForAbsenceTimeout: Int? = null
    private var mThreadWaitTimeout: Int? = null

    override var checkForAbsenceTimeout: Int?
        get() = mCheckForAbsenceTimeout
        set(value) {
            mCheckForAbsenceTimeout = value
        }

    override var threadWaitTimeout: Int?
        get() = mThreadWaitTimeout
        set(value) {
            mThreadWaitTimeout = value
        }

    init {
        Timber.d("init")

        // We set parameters to default values
        mCheckForAbsenceTimeout = Cone2ContactlessReader.CHECK_FOR_ABSENCE_TIMEOUT_DEFAULT
        mThreadWaitTimeout = Cone2ContactlessReader.THREAD_WAIT_TIMEOUT_DEFAULT

        protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443_A.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443_B.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.INNOVATRON_B_PRIME.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.TICKET_CTS_CTM.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.MIFARE.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.FELICA.name] =
            PROTOCOL_DEACTIVATED
        protocolsMap[ParagonSupportedContactlessProtocols.MV5000.name] =
            PROTOCOL_DEACTIVATED
    }

    /**
     * @see AbstractLocalReader.checkCardPresence
     */
    override fun checkCardPresence(): Boolean {
        return isCardDiscovered.get()
    }

    /**
     * @see AbstractLocalReader.getATR
     */
    override fun getATR(): ByteArray? {
        return rfidTag?.atr
    }

    /**
     * @see AbstractLocalReader.openPhysicalChannel
     */
    override fun openPhysicalChannel() {
        Timber.d("openPhysicalChannel")
        isPhysicalChannelOpened.set(true)
    }

    /**
     * @see AbstractLocalReader.closePhysicalChannel
     */
    override fun closePhysicalChannel() {
        Timber.d("closePhysicalChannel")
        isPhysicalChannelOpened.set(false)
    }

    /**
     * @see AbstractLocalReader.isPhysicalChannelOpen
     */
    override fun isPhysicalChannelOpen(): Boolean {
        Timber.d("isPhysicalChannelOpen: ${isPhysicalChannelOpened.get()}")
        return isPhysicalChannelOpened.get()
    }

    /**
     * @see WaitForCardInsertionBlocking.waitForCardPresent
     */
    override fun waitForCardPresent(): Boolean {
        Timber.d("waitForCardPresent")
        var ret = false
        isWaitingForCard.set(true)
//        AskReader.acquireLock()
        // Entering a loop with successive hunts for card
        while (isWaitingForCard.get()) {
            val rfidTag = enterHuntPhase()
            if (rfidTag.communicationMode != RfidTag.CommunicationMode.Unknown) {
                this.rfidTag = rfidTag
                isCardDiscovered.set(true)
                isWaitingForCard.set(false)
                // This allows synchronisation with PLugin when powering off the reader
                ret = true
            }
        }
        // This allows synchronisation with Plugin when powering off the reader
//        AskReader.releaseLock()
        return ret
    }

    /**
     * @see WaitForCardInsertionBlocking.stopWaitForCard
     */
    override fun stopWaitForCard() {
        Timber.d("stopWaitForCard")
        isWaitingForCard.set(false)
    }

    /**
     * Initializes parameters for ISOA/B and Innovatron Protocol then launch the hunt phase
     */
    private fun enterHuntPhase(): RfidTag {
        Timber.d("enterHuntPhase")
        try {
            ParagonReader.acquireLock()
            // 1 - Sets the enter hunt phase parameters to no select application
            reader.cscEnterHuntPhaseParameters(
                0x01.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                byteArrayOf(),
                0x00.toShort(),
                0x00.toByte()
            )

            val search = sCARD_SearchExt()
            search.CONT = 0
            search.OTH = 0
            search.ISOB = protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443.name]!! or
                    protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443_B.name]!!
            search.ISOA = protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443.name]!! or
                    protocolsMap[ParagonSupportedContactlessProtocols.ISO_14443_A.name]!!
            search.TICK = protocolsMap[ParagonSupportedContactlessProtocols.TICKET_CTS_CTM.name]!!
            search.INNO = protocolsMap[ParagonSupportedContactlessProtocols.INNOVATRON_B_PRIME.name]!!
            search.MIFARE = protocolsMap[ParagonSupportedContactlessProtocols.MIFARE.name]!!
            search.MV4k = 0
            search.MV5k = protocolsMap[ParagonSupportedContactlessProtocols.MV5000.name]!!
            search.MONO = 0

            var mask = 0
            if (isCurrentProtocol(ParagonSupportedContactlessProtocols.ISO_14443.name)) {
                mask = mask or Defines.SEARCH_MASK_ISOA or Defines.SEARCH_MASK_ISOB
            } else if (isCurrentProtocol(ParagonSupportedContactlessProtocols.ISO_14443_A.name)) {
                mask = mask or Defines.SEARCH_MASK_ISOA
            } else if (isCurrentProtocol(ParagonSupportedContactlessProtocols.ISO_14443_B.name)) {
                mask = mask or Defines.SEARCH_MASK_ISOB
            }
            if (isCurrentProtocol(ParagonSupportedContactlessProtocols.INNOVATRON_B_PRIME.name)) {
                mask = mask or Defines.SEARCH_MASK_INNO
            }
            if (isCurrentProtocol(ParagonSupportedContactlessProtocols.TICKET_CTS_CTM.name)) {
                mask = mask or Defines.SEARCH_MASK_TICK
            }
            if (isCurrentProtocol(ParagonSupportedContactlessProtocols.MIFARE.name)) {
                mask = mask or Defines.SEARCH_MASK_MIFARE
            }
            if (isCurrentProtocol(ParagonSupportedContactlessProtocols.MV5000.name)) {
                mask = mask or Defines.SEARCH_MASK_MV5K
            }

            val com = ByteArray(1)
            val lpcbAtr = IntArray(1)
            val atr = ByteArray(64)
            val ret: Int = reader.cscSearchCardExt(
                search,
                mask,
                0x00.toByte(),
                HUNT_PHASE_TIMEOUT, // No timeout specified
                com,
                lpcbAtr,
                atr
            )

            return if (ret == Defines.RCSC_Timeout || com[0] == 0x6F.toByte() || com[0] == 0x00.toByte()) {
                Timber.w("Timeout type result")
                isCardDiscovered.set(false)
                RfidTag(0x6F.toByte(), ByteArray(0))
            } else {
                val correctSizedAtr = ByteArray(lpcbAtr[0])
                System.arraycopy(atr, 0, correctSizedAtr, 0, correctSizedAtr.size)
                Timber.d("RFID Tag built ${com[0]} $correctSizedAtr")
                RfidTag(com[0], correctSizedAtr)
            }
        } finally {
            ParagonReader.releaseLock()
        }
    }

    /**
     * @see AbstractLocalReader.transmitApdu
     */
    override fun transmitApdu(apduIn: ByteArray): ByteArray {
        Timber.d("transmitApdu")
        try {
            ParagonReader.acquireLock()
            val dataReceived = ByteArray(256)
            val dataReceivedLength = IntArray(1)

            Timber.d("KEYPLE-APDU-SAM - Data Length to be sent to tag : ${apduIn.size}")
            Timber.d("KEYPLE-APDU-SAM - Data In : ${ByteArrayUtil.toHex(apduIn)}")
            reader.cscISOCommand(apduIn, apduIn.size, dataReceived, dataReceivedLength)
            Timber.d("KEYPLE-APDU-SAM - Data Out : ${ByteArrayUtil.toHex(dataReceived)}")

            val length = dataReceivedLength[0]

            if (length < 2) {
                throw KeypleReaderIOException("Incorrect APDU Answer")
            } else {
                val apduAnswer = ByteArray(length - 1)
                System.arraycopy(dataReceived, 1, apduAnswer, 0, apduAnswer.size)
                return apduAnswer
            }
        } finally {
            ParagonReader.releaseLock()
        }
    }

    /**
     * Clear Paragon reader instance
     */
    private fun clearInstance() {
        ParagonReader.clearInstance()
    }

    /**
     * @see AbstractLocalReader.isCurrentProtocol
     */
    override fun isCurrentProtocol(readerProtocolName: String?): Boolean {
        return readerProtocolName == null || protocolsMap.containsKey(readerProtocolName) && protocolsMap[readerProtocolName] == PROTOCOL_ACTIVATED
    }

    /**
     * @see AbstractLocalReader.isContactless
     */
    override fun isContactless(): Boolean {
        return true
    }

    /**
     * @see AbstractLocalReader.activateReaderProtocol
     */
    override fun activateReaderProtocol(readerProtocolName: String?) {
        Timber.d("$name: Activate protocol $readerProtocolName.")
        if (!protocolsMap.containsKey(readerProtocolName)) {
            throw KeypleReaderProtocolNotSupportedException(readerProtocolName)
        }

        readerProtocolName?.let {
            protocolsMap[it] = PROTOCOL_ACTIVATED
        }
    }

    /**
     * @see AbstractLocalReader.deactivateReaderProtocol
     */
    override fun deactivateReaderProtocol(readerProtocolName: String?) {
        if (!readerProtocolName.isNullOrEmpty() && protocolsMap.containsKey(readerProtocolName)) {
            protocolsMap[readerProtocolName] = PROTOCOL_DEACTIVATED
        }
        Timber.d("$name: Deactivate protocol $readerProtocolName.")
    }

    companion object {
        const val HUNT_PHASE_TIMEOUT = 0xFF.toByte()
        const val PROTOCOL_ACTIVATED = 1.toByte()
        const val PROTOCOL_DEACTIVATED = 0.toByte()
    }

    override fun onStartDetection() {
        // Do nothing
    }

    override fun onStopDetection() {
        // Do nothing
    }

    /**
     * @see AbstractObservableLocalReader.unregister
     */
    override fun unregister() {
        super.unregister()
        clearInstance()
    }

    /**
     * @see AbstractObservableLocalReader.getObservationExceptionHandler
     */
    override fun getObservationExceptionHandler(): ReaderObservationExceptionHandler {
        return readerObservationExceptionHandler
    }
}
