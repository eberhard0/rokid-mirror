package com.eberhard.rokidmirror.phone

/** Same wire format as the glasses app's Protocol.kt; keep the two in step. */
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
}
