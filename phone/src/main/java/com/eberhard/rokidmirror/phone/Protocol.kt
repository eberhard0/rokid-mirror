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
}
