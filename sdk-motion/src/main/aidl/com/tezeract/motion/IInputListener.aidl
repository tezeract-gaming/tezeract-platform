package com.tezeract.motion;

import com.tezeract.input.InputEvent;

interface IInputListener {
    oneway void onInputEvent(in InputEvent event);
}
