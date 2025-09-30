/*
 * Copyright (C) 2022 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

public class Switch extends android.widget.Switch {

    public Switch(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public Switch(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public Switch(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.switchStyle);
    }

    public Switch(Context context) {
        this(context, null);
    }

    @Override
    public void toggle() {
        super.toggle();
    }
}
