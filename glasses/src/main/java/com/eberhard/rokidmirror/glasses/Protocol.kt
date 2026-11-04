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
    const val SERVICE_TYPE = "_rokidmirror._tcp."
    const val KIND_HEADER = 1
    const val KIND_CONFIG = 2
    const val KIND_FRAME = 3
}
