package com.tezeract.input

/**
 * Lifecycle of a single input — mirrors how a physical button reports state.
 *
 * - [PRESS]   emitted once when the source motion enters its active region.
 * - [HOLD]    emitted on every subsequent frame the motion stays active.
 * - [RELEASE] emitted once when the source motion exits the active region.
 */
enum class InputAction { PRESS, HOLD, RELEASE }
