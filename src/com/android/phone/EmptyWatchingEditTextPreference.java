/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.EditTextPreference;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.widget.Button;

public class EmptyWatchingEditTextPreference extends EditTextPreference implements TextWatcher {
    private int mWatchedButton = 0;

    public EmptyWatchingEditTextPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        getEditText().addTextChangedListener(this);
    }

    public EmptyWatchingEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        getEditText().addTextChangedListener(this);
    }

    public EmptyWatchingEditTextPreference(Context context) {
        super(context);
        getEditText().addTextChangedListener(this);
    }

    public void setWatchedButton(int button) {
        mWatchedButton = button;
    }

    private Button getButton() {
        AlertDialog d = (AlertDialog) getDialog();
        if (d != null && mWatchedButton != 0) {
            return d.getButton(mWatchedButton);
        }
        return null;
    }

    @Override
    public  void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        Button b = getButton();
        if (b == null) {
            return;
        }
        boolean empty = s == null || s.toString().trim().isEmpty();
        b.setEnabled(!empty);
    }
}
