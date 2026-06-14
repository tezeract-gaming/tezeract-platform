package com.tezeract.input

/**
 * Receives semantic input events from the Tezeract Motion Engine. Register
 * via `TezeractMotion.addInputListener` and remove with the matching
 * `removeInputListener`. Callbacks fire on a background binder thread —
 * marshal to the UI thread inside the implementation if you need to touch
 * Compose state or Views.
 */
fun interface InputListener {
    fun onInputEvent(event: InputEvent)
}
