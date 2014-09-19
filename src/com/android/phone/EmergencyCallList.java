/*
 *
 * Copyright (c) 2013-2014, The Linux Foundation. All Rights Reserved.
 *
 *
 *Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are
 met:
 * Redistributions of source code must retain the above copyright
       notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above
       copyright notice, this list of conditions and the following
       disclaimer in the documentation and/or other materials provided
       with the distribution.
 * Neither the name of The Linux Foundation, Inc. nor the names of its
       contributors may be used to endorse or promote products derived
       from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 */

package com.android.phone;

import android.util.Log;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.net.Uri;
import android.os.Bundle;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.SystemProperties;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class EmergencyCallList extends ListActivity {

    static final String TAG = "EmergencyCallList";
    static final String ITEM_TEXT = "ItemText";
    static final String PROPERTY_ADDED_ECC_LIST = "persist.radio.user.add.eccList";
    static final int ADD_ECC_NUMBER = 0;
    static final int EMERGENCY_NUMBER_LENGTH = 30;
    private String mDefaultNumbers;
    private int mDefaultLength;
    private AlertDialog mDialog;
    private StringBuilder mAddNumbers;
    private SimpleAdapter mAdapter;
    private ArrayList<HashMap<String, Object>> mNumberList = new ArrayList<HashMap<String, Object>>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.emergency_call_list);
        getDefaultNumberList();
        mAdapter = new SimpleAdapter(this, mNumberList,
                android.R.layout.simple_list_item_1,
                new String[] {ITEM_TEXT},
                new int[] {android.R.id.text1}
        );
        setListAdapter(mAdapter);
    }

    private void getDefaultNumberList() {
        mDefaultNumbers = SystemProperties.get("ril.ecclist");
        mDefaultLength = mDefaultNumbers.split(",").length;
        for (String eccNum : mDefaultNumbers.split(",")) {
            HashMap<String, Object> map = new HashMap<String, Object>();
            map.put(ITEM_TEXT, eccNum);
            if (mNumberList.contains(map)) {
                mDefaultLength--;
            } else {
                mNumberList.add(map);
            }
        }
        Log.d(TAG, "default ecc Numbers " + mDefaultNumbers);
    }

    private void refreshNumberList() {
        mNumberList.subList(mDefaultLength, mNumberList.size()).clear();
        String addNumbers = SystemProperties.get(PROPERTY_ADDED_ECC_LIST);
        if (addNumbers != null && addNumbers.length() > 0) {
            Log.d(TAG, "add  ecc Numbers " + addNumbers);
            for (String eccNumber : addNumbers.split(",")) {
                HashMap<String, Object> map = new HashMap<String, Object>();
                map.put(ITEM_TEXT, eccNumber);
                if (!mNumberList.contains(map)) {
                    mNumberList.add(map);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNumberList();

    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final String number = (String) mNumberList.get(position).get(ITEM_TEXT);
        builder.setTitle(R.string.emergency_call_number);
        builder.setMessage(number);
        builder.setPositiveButton(R.string.emergency_call,
                new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (number != null) {
                            Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY);
                            intent.setData(Uri.fromParts("tel", number, null));
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            finish();
                        }
                    }
                });
        if (mDefaultLength > position) {
            builder.setNegativeButton(R.string.cancel, null);
        } else {
            builder.setNegativeButton(R.string.delete, new OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    if (number != null) {
                        deleteEmergencyNumber(number);
                    }
                }
            });
        }
        builder.create().show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.emergency_call_list_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_emergency_number:
                showDialog(ADD_ECC_NUMBER);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case ADD_ECC_NUMBER:
                LayoutInflater inflater = LayoutInflater.from(this);
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                View view = inflater.inflate(R.layout.new_emergency_call_number, null);
                final EditText edit = (EditText) view.findViewById(R.id.edit);
                builder.setPositiveButton(android.R.string.ok,
                        new OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                String number = edit.getText().toString();
                                if (number.trim().length() > 0) {
                                    addEmergencyNumber(number.trim());
                                }
                                refreshNumberList();
                                edit.setText("");
                                mDialog.cancel();
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        edit.setText("");
                        mDialog.cancel();
                    }
                });
                mDialog = builder.setView(view).setTitle(R.string.new_emergency_number).create();
                return mDialog;
            default:
        }

        return null;
    }

    private void addEmergencyNumber(String number) {
        Log.d(TAG, "addEmergencyNumber.number=" + number);

        if (EMERGENCY_NUMBER_LENGTH < number.length()) {
            Toast.makeText(this, R.string.emergency_number_save_error, Toast.LENGTH_LONG).show();
            return;
        }

        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put(ITEM_TEXT, number);
        if (!mNumberList.contains(map)) {
            String addNumbers = SystemProperties.get(PROPERTY_ADDED_ECC_LIST);
            if (addNumbers == null || addNumbers.length() <= 0) {
                mAddNumbers = new StringBuilder(number);
            }
            else {
                mAddNumbers = new StringBuilder(addNumbers);
                mAddNumbers.append(",");
                mAddNumbers.append(number);
            }

            Log.d(TAG, "addEmergencyNumber.PROPERTY_ADDED_ECC_LIST=" + mAddNumbers.toString());
            SystemProperties.set(PROPERTY_ADDED_ECC_LIST, mAddNumbers.toString());
        }
        else {
            Toast toast = Toast.makeText(this, R.string.emergency_number_exist, Toast.LENGTH_LONG);
            toast.show();
        }
    }

    private void deleteEmergencyNumber(String numberDelete) {
        ArrayList<String> addEccNumber = new ArrayList<String>();
        String addNumbers = SystemProperties.get(PROPERTY_ADDED_ECC_LIST);
        for (String eccNum : addNumbers.split(",")) {
            if (!addEccNumber.contains(eccNum)) {
                addEccNumber.add(eccNum);
            }
        }
        addEccNumber.remove(numberDelete);
        Log.d(TAG, "added emergency number is " + addEccNumListToString(addEccNumber));

        SystemProperties.set(PROPERTY_ADDED_ECC_LIST, addEccNumListToString(addEccNumber));
        refreshNumberList();
    }

    private String addEccNumListToString(ArrayList<String> addEccNumList) {
        String str = null;
        String string = Arrays.toString(addEccNumList.toArray());
        string = string.replace("[", "");
        string = string.replace("]", "");
        string = string.trim();
        String[] stringArray = string.split(", ");
        for (int i = 0; i < stringArray.length; i++) {
            if (i == 0) {
                str = stringArray[i];
            }
            else {
                str = str + "," + stringArray[i];
            }
        }
        return str;
    }
}
