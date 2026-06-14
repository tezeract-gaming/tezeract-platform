package com.tezeract.input

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One semantic input event delivered to a game.
 *
 * @property input      which logical button fired
 * @property action     PRESS / HOLD / RELEASE — see [InputAction]
 * @property confidence 0..1 — derived from the source motion's detection score
 *                      and (when relevant) how deeply into the active region
 *                      the user is. Games can use this for analog response or
 *                      ignore it entirely.
 * @property bodyId     id of the tracked body that produced this event
 * @property timestamp  `System.nanoTime()` when the source frame was processed
 */
@Parcelize
data class InputEvent(
    val input: TezeractInput,
    val action: InputAction,
    val confidence: Float,
    val bodyId: Int,
    val timestamp: Long,
) : Parcelable
