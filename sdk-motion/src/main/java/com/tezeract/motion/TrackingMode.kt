package com.tezeract.motion

enum class TrackingMode(val value: Int) {
    BODY_ONLY(0),
    BODY_AND_HANDS(1),
    FULL(2);  // body + hands + face

    companion object {
        fun fromValue(value: Int): TrackingMode =
            entries.firstOrNull { it.value == value } ?: BODY_ONLY
    }
}
