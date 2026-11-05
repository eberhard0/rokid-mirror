package com.eberhard.rokidmirror.glasses

/**
 * The wire format between Rokid Mirror (phone) and Rokid Screen (glasses), over one TCP
 * connection the phone opens to the glasses. Big-endian, like DataOutputStream.
 *
 *   "RMV1"                                   once, when the connection opens
 *   repeated packets:
 *     int32 kind   1 = stream header, 2 = codec config (SPS/PPS), 3 = frame
 *     int32 length
 *     int64 presentation time (µs; 0 for header/config)
 *     bytes payload (header: int32 width, int32 height)
 *
 * A new stream header may arrive mid-session (the phone was folded or unfolded): the decoder
 * is rebuilt, and a config packet follows.
 */
object Protocol {
    const val MAGIC = "RMV1"
    const val PORT = 7070
    /** UDP: the phone broadcasts "RMDISC?" here, the glasses answer "RMDISC <port>". */
    const val PROBE_PORT = 7071
    const val SERVICE_TYPE = "_rokidmirror._tcp."
    const val KIND_HEADER = 1
    const val KIND_CONFIG = 2
    const val KIND_FRAME = 3
    /** No payload: sent every 2 s when nothing else is, so a dead link is noticed within 6 s. */
    const val KIND_PING = 4
    /** No payload: the phone is stopping; the glasses go back to waiting at once. */
    const val KIND_BYE = 5
    const val SILENCE_TIMEOUT_MS = 6000
    /** DeX mode: the phone relays scrcpy's video socket here (see ~/bin/rokid-dex.sh). */
    const val DEX_PORT = 27184
    /** UDP: the glasses broadcast "RMDEX?" here; the phone running rokid-dex.sh answers "RMDEX <port>". */
    const val DEX_DISCOVERY_PORT = 7072
    /** Loopback control line of the on-phone helper "dexd": start / stop / status. */
    const val DEXD_CONTROL_PORT = 27185
}
