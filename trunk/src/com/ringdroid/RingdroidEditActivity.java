/*
 * Copyright (C) 2008 Google Inc.
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

package com.ringdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Contacts.People;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.ringdroid.soundfile.CheapSoundFile;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.PrintWriter;
import java.util.HashMap;

/**
 * The activity for the Ringdroid main editor window.  Keeps track of
 * the waveform display, current horizontal offset, marker handles,
 * start / end text boxes, and handles all of the buttons and controls.
 */
public class RingdroidEditActivity extends Activity
    implements MarkerView.MarkerListener,
               WaveformView.WaveformListener
{
    private long mLoadingStartTime;
    private long mLoadingLastUpdateTime;
    private boolean mLoadingKeepGoing;
    private ProgressDialog mProgressDialog;
    private CheapSoundFile mSoundFile;
    private File mFile;
    private String mFilename;
    private String mArtist;
    private String mTitle;
    private String mExtension;
    private String mRecordingFilename;
    private Uri mRecordingUri;
    private boolean mWasGetContentIntent;
    private WaveformView mWaveformView;
    private MarkerView mStartMarker;
    private MarkerView mEndMarker;
    private TextView mStartText;
    private TextView mEndText;
    private TextView mInfo;
    private ImageButton mPlayButton;
    private ImageButton mRewindButton;
    private ImageButton mFfwdButton;
    private ImageButton mZoomInButton;
    private ImageButton mZoomOutButton;
    private ImageButton mSaveButton;
    private boolean mKeyDown;
    private String mCaption = "";
    private int mWidth;
    private int mMaxPos;
    private int mStartPos;
    private int mEndPos;
    private int mLastDisplayedStartPos;
    private int mLastDisplayedEndPos;
    private int mOffset;
    private int mOffsetGoal;
    private int mPlayStartMsec;
    private int mPlayEndMsec;
    private Handler mHandler;
    private MediaPlayer mPlayer;
    private boolean mTouchDragging;
    private float mTouchStart;
    private int mTouchInitialOffset;
    private int mTouchInitialStartPos;
    private int mTouchInitialEndPos;
    private long mWaveformTouchStartMsec;

    private static final int kMarkerLeftInset = 46;
    private static final int kMarkerRightInset = 48;
    private static final int kMarkerTopOffset = 10;
    private static final int kMarkerBottomOffset = 10;

    // Menu commands
    private static final int CMD_SAVE = 1;
    private static final int CMD_RESET = 2;
    private static final int CMD_ABOUT = 3;

    // Result codes
    private static final int REQUEST_CODE_RECORD = 1;
    private static final int REQUEST_CODE_CHOOSE_CONTACT = 2;

    /**
     * This is a special intent action that means "edit a sound file".
     */
    public static final String EDIT =
        "com.ringdroid.action.EDIT";

    /**
     * Preference names
     */
    public static final String PREF_SUCCESS_COUNT = "success_count";

    //
    // Public methods and protected overrides
    //

    /** Called with the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mRecordingFilename = null;
        mRecordingUri = null;

        Intent intent = getIntent();
        mFilename = intent.getData().toString();

        // If the Ringdroid media select activity was launched via a
        // GET_CONTENT intent, then we shouldn't display a "saved"
        // message when the user saves, we should just return whatever
        // they create.
        mWasGetContentIntent = intent.getBooleanExtra(
            "was_get_content_intent", false);

        mSoundFile = null;
        mKeyDown = false;

        if (mFilename.equals("record")) {
            try {
                Intent recordIntent = new Intent(
                    MediaStore.Audio.Media.RECORD_SOUND_ACTION);
                startActivityForResult(recordIntent, REQUEST_CODE_RECORD);
            } catch (Exception e) {
                showFinalAlert(e, R.string.record_error);
            }
        }

        loadGui();

        mHandler = new Handler();
        mHandler.postDelayed(mTimerRunnable, 100);

        if (!mFilename.equals("record")) {
            loadFromFile();
        }
    }

    /** Called with the activity is finally destroyed. */
    @Override
    protected void onDestroy() {
        if (mPlayer != null) {
            handleStop();
        }

        if (mRecordingFilename != null) {
            try {
                if (!new File(mRecordingFilename).delete()) {
                    showFinalAlert(new Exception(), R.string.delete_tmp_error);
                }

                getContentResolver().delete(mRecordingUri, null, null);
            } catch (SecurityException e) {
                showFinalAlert(e, R.string.delete_tmp_error);
            }
        }

        super.onDestroy();
    }

    /** Called with an Activity we started with an Intent returns. */
    @Override
    protected void onActivityResult(int requestCode,
                                    int resultCode,
                                    Intent dataIntent) {
        if (requestCode == REQUEST_CODE_CHOOSE_CONTACT) {
            // The user finished saving their ringtone and they're
            // just applying it to a contact.  When they return here,
            // they're done.
            finish();
            return;
        }

        if (requestCode != REQUEST_CODE_RECORD) {
            return;
        }

        if (resultCode != RESULT_OK) {
            finish();
            return;
        }

        if (dataIntent == null) {
            finish();
            return;
        }

        // Get the recorded file and open it, but save the uri and
        // filename so that we can delete them when we exit; the
        // recorded file is only temporary and only the edited & saved
        // ringtone / other sound will stick around.
        mRecordingUri = dataIntent.getData();
        mRecordingFilename = getFilenameFromUri(mRecordingUri);
        mFilename = mRecordingFilename;
        loadFromFile();
    }

    /**
     * Called when the orientation changes and/or the keyboard is shown
     * or hidden.  We don't need to recreate the whole activity in this
     * case, but we do need to redo our layout somewhat.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        final int saveZoomLevel = mWaveformView.getZoomLevel();
        super.onConfigurationChanged(newConfig);

        loadGui();
        enableZoomButtons();

        new Handler().postDelayed(new Runnable() {
                public void run() {
                    mStartMarker.requestFocus();
                    markerFocus(mStartMarker);

                    mWaveformView.setZoomLevel(saveZoomLevel);
                    mWaveformView.recomputeHeights();

                    updateDisplay();
                }
            }, 500);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuItem item;

        item = menu.add(0, CMD_SAVE, 0, R.string.menu_save);
        item.setIcon(R.drawable.menu_save);

        item = menu.add(0, CMD_RESET, 0, R.string.menu_reset);
        item.setIcon(R.drawable.menu_reset);

        item = menu.add(0, CMD_ABOUT, 0, R.string.menu_about);
        item.setIcon(R.drawable.menu_about);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(CMD_SAVE).setVisible(true);
        menu.findItem(CMD_RESET).setVisible(true);
        menu.findItem(CMD_ABOUT).setVisible(true);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case CMD_SAVE:
            onSave();
            return true;
        case CMD_RESET:
            resetPositions();
            mOffsetGoal = 0;
            updateDisplay();
            return true;
        case CMD_ABOUT:
            onAbout(this);
            return true;
        default:
            return false;
        }
    }

    //
    // WaveformListener
    //

    public void waveformTouchStart(float x) {
        mTouchDragging = true;
        mTouchStart = x;
        mTouchInitialOffset = mOffset;
        mWaveformTouchStartMsec = System.currentTimeMillis();
    }

    public void waveformTouchMove(float x) {
        mOffset = trap((int)(mTouchInitialOffset + (mTouchStart - x)));
        updateDisplay();
    }

    public void waveformTouchEnd() {
        mTouchDragging = false;
        mOffsetGoal = mOffset;

        long elapsedMsec = System.currentTimeMillis() -
            mWaveformTouchStartMsec;
        if (elapsedMsec < 300) {
            if (mPlayer != null) {
                int seekMsec = mWaveformView.pixelsToMillisecs(
                    (int)(mTouchStart + mOffset));
                mPlayer.seekTo(seekMsec);
            } else {
                onPlay((int)(mTouchStart + mOffset));
            }
        }
    }

    //
    // MarkerListener
    //

    /**
     * Every time we get a message that our marker drew, see if we need to
     * animate and trigger another redraw.
     */
    public void markerDraw() {
        mWidth = mWaveformView.getMeasuredWidth();
        if (mOffsetGoal != mOffset && !mKeyDown)
            updateDisplay();
        else if (mPlayer != null) {
            updateDisplay();
        }
    }

    public void markerTouchStart(MarkerView marker, float x) {
        mTouchDragging = true;
        mTouchStart = x;
        mTouchInitialStartPos = mStartPos;
        mTouchInitialEndPos = mEndPos;
    }

    public void markerTouchMove(MarkerView marker, float x) {
        float delta = x - mTouchStart;

        if (marker == mStartMarker) {
            mStartPos = trap((int)(mTouchInitialStartPos + delta));
            mEndPos = trap((int)(mTouchInitialEndPos + delta));
        } else {
            mEndPos = trap((int)(mTouchInitialEndPos + delta));
            if (mEndPos < mStartPos)
                mEndPos = mStartPos;
        }

        updateDisplay();
    }

    public void markerTouchEnd(MarkerView marker) {
        mTouchDragging = false;
        if (marker == mStartMarker) {
            setOffsetGoalStart();
        } else {
            setOffsetGoalEnd();
        }
    }

    public void markerLeft(MarkerView marker, int velocity) {
        mKeyDown = true;

        if (marker == mStartMarker) {
            int saveStart = mStartPos;
            mStartPos = trap(mStartPos - velocity);
            mEndPos = trap(mEndPos - (saveStart - mStartPos));
            setOffsetGoalStart();
        }

        if (marker == mEndMarker) {
            if (mEndPos == mStartPos) {
                mStartPos = trap(mStartPos - velocity);
                mEndPos = mStartPos;
            } else {
                mEndPos = trap(mEndPos - velocity);
            }

            setOffsetGoalEnd();
        }

        updateDisplay();
    }

    public void markerRight(MarkerView marker, int velocity) {
        mKeyDown = true;

        if (marker == mStartMarker) {
            int saveStart = mStartPos;
            mStartPos += velocity;
            if (mStartPos > mMaxPos)
                mStartPos = mMaxPos;
            mEndPos += (mStartPos - saveStart);
            if (mEndPos > mMaxPos)
                mEndPos = mMaxPos;

            setOffsetGoalStart();
        }

        if (marker == mEndMarker) {
            mEndPos += velocity;
            if (mEndPos > mMaxPos)
                mEndPos = mMaxPos;

            setOffsetGoalEnd();
        }

        updateDisplay();
    }

    public void markerEnter(MarkerView marker) {
    }

    public void markerKeyUp() {
        mKeyDown = false;
        updateDisplay();
    }

    public void markerFocus(MarkerView marker) {
        mKeyDown = false;
        if (marker == mStartMarker) {
            setOffsetGoalStartNoUpdate();
        } else {
            setOffsetGoalEndNoUpdate();
        }

        // Delay updaing the display because if this focus was in
        // response to a touch event, we want to receive the touch
        // event too before updating the display.
        new Handler().postDelayed(new Runnable() {
                public void run() {
                    updateDisplay();
                }
            }, 100);
    }

    //
    // Static About dialog method, also called from RingdroidSelectActivity
    //
    
    public static void onAbout(final Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_text)
            .setPositiveButton(R.string.alert_ok_button, null)
            .setCancelable(false)
            .show();        
    }

    //
    // Internal methods
    //

    /**
     * Called from both onCreate and onConfigurationChanged
     * (if the user switched layouts)
     */
    private void loadGui() {
        // Inflate our UI from its XML layout description.
        setContentView(R.layout.editor);

        mStartText = (TextView)findViewById(R.id.starttext);
        mStartText.addTextChangedListener(mTextWatcher);
        mEndText = (TextView)findViewById(R.id.endtext);
        mEndText.addTextChangedListener(mTextWatcher);

        mPlayButton = (ImageButton)findViewById(R.id.play);
        mPlayButton.setOnClickListener(mPlayListener);
        mRewindButton = (ImageButton)findViewById(R.id.rew);
        mRewindButton.setOnClickListener(mRewindListener);
        mFfwdButton = (ImageButton)findViewById(R.id.ffwd);
        mFfwdButton.setOnClickListener(mFfwdListener);
        mZoomInButton = (ImageButton)findViewById(R.id.zoom_in);
        mZoomInButton.setOnClickListener(mZoomInListener);
        mZoomOutButton = (ImageButton)findViewById(R.id.zoom_out);
        mZoomOutButton.setOnClickListener(mZoomOutListener);
        mSaveButton = (ImageButton)findViewById(R.id.save);
        mSaveButton.setOnClickListener(mSaveListener);

        TextView markStartButton = (TextView) findViewById(R.id.mark_start);
        markStartButton.setOnClickListener(mMarkStartListener);
        TextView markEndButton = (TextView) findViewById(R.id.mark_end);
        markEndButton.setOnClickListener(mMarkStartListener);

        enableDisableButtons();

        mWaveformView = (WaveformView)findViewById(R.id.waveform);
        mWaveformView.setListener(this);

        mInfo = (TextView)findViewById(R.id.info);
        mInfo.setText(mCaption);

        mMaxPos = 0;
        mLastDisplayedStartPos = -1;
        mLastDisplayedEndPos = -1;

        if (mSoundFile != null) {
            mWaveformView.setSoundFile(mSoundFile);
            mWaveformView.recomputeHeights();
            mMaxPos = mWaveformView.maxPos();
        }

        mStartMarker = (MarkerView)findViewById(R.id.startmarker);
        mStartMarker.setListener(this);
        mStartMarker.setAlpha(255);
        mStartMarker.setFocusable(true);
        mStartMarker.setFocusableInTouchMode(true);

        mEndMarker = (MarkerView)findViewById(R.id.endmarker);
        mEndMarker.setListener(this);
        mEndMarker.setAlpha(255);
        mEndMarker.setFocusable(true);
        mEndMarker.setFocusableInTouchMode(true);

        updateDisplay();
    }

    private void loadFromFile() {
        mFile = new File(mFilename);
        mExtension = getExtensionFromFilename(mFilename);

        getTitleAndArtistFromFilename(mFilename);
        String titleLabel = mTitle;
        if (mArtist != null && mArtist.length() > 0) {
            titleLabel += " - " + mArtist;
        }
        setTitle(titleLabel);

        mLoadingStartTime = System.currentTimeMillis();
        mLoadingLastUpdateTime = System.currentTimeMillis();
        mLoadingKeepGoing = true;
        mProgressDialog = new ProgressDialog(RingdroidEditActivity.this);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setTitle(R.string.progress_dialog_loading);
        mProgressDialog.setCancelable(true);
        mProgressDialog.setOnCancelListener(
            new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    mLoadingKeepGoing = false;
                }
            });
        mProgressDialog.show();

        final CheapSoundFile.ProgressListener listener =
            new CheapSoundFile.ProgressListener() {
                public boolean reportProgress(double fractionComplete) {
                    long now = System.currentTimeMillis();
                    if (now - mLoadingLastUpdateTime > 100) {
                        mProgressDialog.setProgress(
                            (int)(mProgressDialog.getMax() *
                                  fractionComplete));
                        mLoadingLastUpdateTime = now;
                    }
                    return mLoadingKeepGoing;
                }
            };

        // Load the sound file in a background thread
        new Thread() { 
            public void run() { 
                try {
                    System.out.println("Thread run");
                    mSoundFile = CheapSoundFile.create(mFile.getAbsolutePath(),
                                                       listener);
                    System.out.println("Created sound file " + mSoundFile);
                } catch (Exception e) {
                    mProgressDialog.dismiss();
                    e.printStackTrace();
                    mInfo.setText(e.toString());
                    return;
                }
                System.out.println("Thread done");
                mProgressDialog.dismiss(); 
                if (mLoadingKeepGoing) {
                    System.out.println("Finish opening " + mSoundFile);
                    Runnable runnable = new Runnable() {
                            public void run() {
                                finishOpeningSoundFile();
                            }
                        };
                    mHandler.post(runnable);
                } else {
                    RingdroidEditActivity.this.finish();
                }
            } 
        }.start();
    }

    private void finishOpeningSoundFile() {
        mWaveformView.setSoundFile(mSoundFile);
        mMaxPos = mWaveformView.maxPos();
        mLastDisplayedStartPos = -1;
        mLastDisplayedEndPos = -1;

        mTouchDragging = false;

        mOffset = 0;
        mOffsetGoal = 0;
        resetPositions();
        if (mEndPos > mMaxPos)
            mEndPos = mMaxPos;

        mCaption = 
            mSoundFile.getFiletype() + ", " +
            mSoundFile.getSampleRate() + " Hz, " +
            mSoundFile.getAvgBitrateKbps() + " kbps, " +
            formatTime(mMaxPos) + " seconds";
        mInfo.setText(mCaption);

        updateDisplay();
    }

    private synchronized void updateDisplay() {
        if (mPlayer != null) {
            int now = mPlayer.getCurrentPosition();
            int frames = mWaveformView.millisecsToPixels(now);
            mWaveformView.setPlayback(frames);
            setOffsetGoalNoUpdate(frames - mWidth / 2);
            if (now >= mPlayEndMsec) {
                handleStop();
            }
        }

        if (!mTouchDragging) {
            int offsetDelta = mOffsetGoal - mOffset;
            if (offsetDelta > 10)
                offsetDelta = offsetDelta / 10;
            else if (offsetDelta > 0)
                offsetDelta = 1;
            else if (offsetDelta < -10)
                offsetDelta = offsetDelta / 10;
            else if (offsetDelta < 0)
                offsetDelta = -1;
            else
                offsetDelta = 0;
            mOffset += offsetDelta;
        }

        mWaveformView.setParameters(mStartPos, mEndPos, mOffset);
        mWaveformView.invalidate();

        int startX = mStartPos - mOffset - kMarkerLeftInset;
        if (startX + mStartMarker.getWidth() >= 0) {
            mStartMarker.setAlpha(255);
        } else {
            mStartMarker.setAlpha(0);
            startX = 0;
        }

        int endX = mEndPos - mOffset - mEndMarker.getWidth() +
            kMarkerRightInset;
        if (endX + mEndMarker.getWidth() >= 0) {
            mEndMarker.setAlpha(255);
        } else {
            mEndMarker.setAlpha(0);
            endX = 0;
        }

        mStartMarker.setLayoutParams(
            new AbsoluteLayout.LayoutParams(
                AbsoluteLayout.LayoutParams.WRAP_CONTENT,
                AbsoluteLayout.LayoutParams.WRAP_CONTENT,
                startX,
                kMarkerTopOffset));

        mEndMarker.setLayoutParams(
            new AbsoluteLayout.LayoutParams(
                AbsoluteLayout.LayoutParams.WRAP_CONTENT,
                AbsoluteLayout.LayoutParams.WRAP_CONTENT,
                endX,
                mWaveformView.getMeasuredHeight() -
                mEndMarker.getHeight() - kMarkerBottomOffset));
    }

    private Runnable mTimerRunnable = new Runnable() {
            public void run() {
                // Updating an EditText is slow on Android.  Make sure
                // we only do the update if the text has actually changed.
                if (mStartPos != mLastDisplayedStartPos &&
                    !mStartText.hasFocus()) {
                    mStartText.setText(formatTime(mStartPos));
                    mLastDisplayedStartPos = mStartPos;
                }

                if (mEndPos != mLastDisplayedEndPos &&
                    !mEndText.hasFocus()) {
                    mEndText.setText(formatTime(mEndPos));
                    mLastDisplayedEndPos = mEndPos;
                }

                mHandler.postDelayed(mTimerRunnable, 100);
            }
        };

    private void enableDisableButtons() {
        if (mPlayer != null) {
            mPlayButton.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            mPlayButton.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void resetPositions() {
        mStartPos = mWaveformView.secondsToPixels(0.0);
        mEndPos = mWaveformView.secondsToPixels(15.0);
    }

    private int trap(int pos) {
        if (pos < 0)
            return 0;
        if (pos > mMaxPos)
            return mMaxPos;
        return pos;
    }

    private void setOffsetGoalStart() {
        setOffsetGoal(mStartPos - mWidth / 2);
    }

    private void setOffsetGoalStartNoUpdate() {
        setOffsetGoalNoUpdate(mStartPos - mWidth / 2);
    }

    private void setOffsetGoalEnd() {
        setOffsetGoal(mEndPos - mWidth / 2);
    }

    private void setOffsetGoalEndNoUpdate() {
        setOffsetGoalNoUpdate(mEndPos - mWidth / 2);
    }

    private void setOffsetGoal(int offset) {
        setOffsetGoalNoUpdate(offset);
        updateDisplay();
    }

    private void setOffsetGoalNoUpdate(int offset) {
        if (mTouchDragging) {
            return;
        }

        mOffsetGoal = offset;
        if (mOffsetGoal + mWidth / 2 > mMaxPos)
            mOffsetGoal = mMaxPos - mWidth / 2;
        if (mOffsetGoal < 0)
            mOffsetGoal = 0;
    }

    private String formatTime(int pixels) {
        if (mSoundFile != null) {
            return formatDecimal(mWaveformView.pixelsToSeconds(pixels));
        } else {
            return "";
        }
    }

    private String formatDecimal(double x) {
        int xWhole = (int)x;
        int xFrac = (int)(100 * (x - xWhole) + 0.5);

        if (xFrac >= 100) {
            xWhole++; //Round up
            xFrac -= 100; //Now we need the remainder after the round up
            if (xFrac < 10) {
                xFrac *= 10; //we need a fraction that is 2 digits long
            }
        }

        if (xFrac < 10)
            return xWhole + ".0" + xFrac;
        else
            return xWhole + "." + xFrac;
    }

    private synchronized void handleStop() {
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer = null;
        }
        mWaveformView.setPlayback(-1);
        enableDisableButtons();
    }

    private synchronized void onPlay(int startPosition) {
        if (mPlayer != null) {
            handleStop();
            return;
        }

        MediaPlayer player = new MediaPlayer();
        try {
            mPlayStartMsec = mWaveformView.pixelsToMillisecs(startPosition);
            if (startPosition < mStartPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mStartPos);
            } else if (startPosition > mEndPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mMaxPos);
            } else {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mEndPos);
            }

            player.setDataSource(mFile.getAbsolutePath());
            player.setAudioStreamType(AudioManager.STREAM_RING);
            player.prepare();
            player.seekTo(mPlayStartMsec);
            mPlayer = player;
            mPlayer.setOnCompletionListener(new OnCompletionListener() {
                    public synchronized void onCompletion(MediaPlayer arg0) {
                        handleStop();
                    }
                });
            mPlayer.start();
            updateDisplay();
            enableDisableButtons();
        } catch (Exception e) {
            showFinalAlert(e, R.string.play_error);
            return;
        }
    }

    /**
     * Show a "final" alert dialog that will exit the activity
     * after the user clicks on the OK button.  If an exception
     * is passed, it's assumed to be an error condition, and the
     * dialog is presented as an error, and the stack trace is
     * logged.  If there's no exception, it's a success message.
     */
    private void showFinalAlert(Exception e, CharSequence message) {
        CharSequence title;
        if (e != null) {
            Log.e("Ringdroid", "Error: " + message);
            Log.e("Ringdroid", getStackTrace(e));
            title = getResources().getText(R.string.alert_title_failure);
            setResult(RESULT_CANCELED, new Intent());
        } else {
            Log.i("Ringdroid", "Success: " + message);
            title = getResources().getText(R.string.alert_title_success);
        }

        new AlertDialog.Builder(RingdroidEditActivity.this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(
                R.string.alert_ok_button,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                                        int whichButton) {
                        finish();
                    }
                })
            .setCancelable(false)
            .show();
    }

    private void showFinalAlert(Exception e, int messageResourceId) {
        showFinalAlert(e, getResources().getText(messageResourceId));
    }

    private String makeRingtoneFilename(CharSequence title, String extension,
                                        int fileType) {
        String parentdir;
        switch(fileType) {
        default:
        case FileSaveDialog.FILE_TYPE_MUSIC:
            parentdir = "/sdcard/media/audio/music";
            break;
        case FileSaveDialog.FILE_TYPE_ALARM:
            parentdir = "/sdcard/media/audio/alarms";
            break;
        case FileSaveDialog.FILE_TYPE_NOTIFICATION:
            parentdir = "/sdcard/media/audio/notifications";
            break;
        case FileSaveDialog.FILE_TYPE_RINGTONE:
            parentdir = "/sdcard/media/audio/ringtones";
            break;
        }

        // Create the parent directory
        (new File(parentdir)).mkdirs();

        // Turn the title into a filename
        String filename = "";
        for (int i = 0; i < title.length(); i++) {
            if (Character.isLetterOrDigit(title.charAt(i))) {
                filename += title.charAt(i);
            }
        }

        // Try to make the filename unique
        String path = null;
        for (int i = 0; i < 100; i++) {
            String testPath;
            if (i > 0)
                testPath = parentdir + "/" + filename + i + extension;
            else
                testPath = parentdir + "/" + filename + extension;

            try {
                RandomAccessFile f = new RandomAccessFile(
                    new File(testPath), "r");
            } catch (Exception e) {
                // Good, the file didn't exist
                path = testPath;
                break;
            }
        }

        return path;
    }

    private void saveRingtone(CharSequence title, final int fileType) {
        String path = makeRingtoneFilename(title, mExtension, fileType);

        if (path == null) {
            showFinalAlert(new Exception(), R.string.no_unique_filename);
            return;
        }

        double startTime = mWaveformView.pixelsToSeconds(mStartPos);
        double endTime = mWaveformView.pixelsToSeconds(mEndPos);
        int startFrame = mWaveformView.secondsToFrames(startTime);
        int endFrame = mWaveformView.secondsToFrames(endTime);

        File outFile = new File(path);
        try {
            mSoundFile.WriteFile(
                outFile,
                startFrame,
                endFrame - startFrame);
        } catch (Exception e) {
            showFinalAlert(e, R.string.write_error);
            return;
        }

        long length = outFile.length();
        if (length <= 512) {
            outFile.delete();
            new AlertDialog.Builder(this)
                .setTitle(R.string.alert_title_failure)
                .setMessage(R.string.too_small_error)
                .setPositiveButton(R.string.alert_ok_button, null)
                .setCancelable(false)
                .show();
            return;
        }

        // Create the database record, pointing to the existing file path

        long fileSize = outFile.length();
        String mimeType = "audio/mpeg";

        String artist = "" + getResources().getText(R.string.artist_name);

        int duration = (int)(endTime - startTime + 0.5);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, path);
        values.put(MediaStore.MediaColumns.TITLE, title.toString());
        values.put(MediaStore.MediaColumns.SIZE, fileSize);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        values.put(MediaStore.Audio.Media.ARTIST, artist);
        values.put(MediaStore.Audio.Media.DURATION, duration);

        values.put(MediaStore.Audio.Media.IS_RINGTONE,
                   fileType == FileSaveDialog.FILE_TYPE_RINGTONE);
        values.put(MediaStore.Audio.Media.IS_NOTIFICATION,
                   fileType == FileSaveDialog.FILE_TYPE_NOTIFICATION);
        values.put(MediaStore.Audio.Media.IS_ALARM,
                   fileType == FileSaveDialog.FILE_TYPE_ALARM);
        values.put(MediaStore.Audio.Media.IS_MUSIC,
                   fileType == FileSaveDialog.FILE_TYPE_MUSIC);

        // Insert it into the database
        Uri uri = MediaStore.Audio.Media.getContentUriForPath(path);
        final Uri newUri = getContentResolver().insert(uri, values);
        setResult(RESULT_OK, new Intent().setData(newUri));

        // Update a preference that counts how many times we've
        // successfully saved a ringtone or other audio
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        int successCount = prefs.getInt(PREF_SUCCESS_COUNT, 0);
        SharedPreferences.Editor prefsEditor = prefs.edit();
        prefsEditor.putInt(PREF_SUCCESS_COUNT, successCount + 1);
        prefsEditor.commit();

        // If Ringdroid was launched to get content, just return
        if (mWasGetContentIntent) {
            finish();
            return;
        }

        // There's nothing more to do with music or an alarm.  Show a
        // success message and then quit.
        if (fileType == FileSaveDialog.FILE_TYPE_MUSIC ||
            fileType == FileSaveDialog.FILE_TYPE_ALARM) {
            Toast.makeText(this,
                           R.string.save_success_message,
                           Toast.LENGTH_SHORT)
                .show();
            finish();
            return;
        }

        // If it's a notification, give the user the option of making
        // this their default notification.  If they say no, we're finished.
        if (fileType == FileSaveDialog.FILE_TYPE_NOTIFICATION) {
            new AlertDialog.Builder(RingdroidEditActivity.this)
                .setTitle(R.string.alert_title_success)
                .setMessage(R.string.set_default_notification)
                .setPositiveButton(R.string.alert_yes_button,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog,
                                            int whichButton) {
                            RingtoneManager.setActualDefaultRingtoneUri(
                                RingdroidEditActivity.this,
                                RingtoneManager.TYPE_NOTIFICATION,
                                newUri);
                            finish();
                        }
                    })
                .setNegativeButton(
                    R.string.alert_no_button,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog,
                                            int whichButton) {
                            finish();
                        }
                    })
                .setCancelable(false)
                .show();
            return;
        }

        // If we get here, that means the type is a ringtone.  There are
        // three choices: make this your default ringtone, assign it to a
        // contact, or do nothing.

        final Handler handler = new Handler() {
                public void handleMessage(Message response) {
                    int actionId = response.arg1;
                    switch (actionId) {
                    case R.id.button_make_default:
                        RingtoneManager.setActualDefaultRingtoneUri(
                            RingdroidEditActivity.this,
                            RingtoneManager.TYPE_RINGTONE,
                            newUri);
                        Toast.makeText(
                            RingdroidEditActivity.this,
                            R.string.default_ringtone_success_message,
                            Toast.LENGTH_SHORT)
                            .show();
                        finish();
                        break;
                    case R.id.button_choose_contact:
                        chooseContactForRingtone(newUri);
                        break;
                    default:
                    case R.id.button_do_nothing:
                        finish();
                        break;
                    }
                }
            };
        Message message = Message.obtain(handler);
        AfterSaveActionDialog dlog = new AfterSaveActionDialog(
            this, message);
        dlog.show();
    }

    private void chooseContactForRingtone(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_EDIT, uri);
            intent.setClassName(
                "com.ringdroid",
                "com.ringdroid.ChooseContactActivity");
            startActivityForResult(intent, REQUEST_CODE_CHOOSE_CONTACT);
        } catch (Exception e) {
            Log.e("Ringdroid", "Couldn't open Choose Contact window");
        }
    }

    private void onSave() {
        final Activity activity = this;
        final Handler finishHandler = new Handler() {
                public void handleMessage(Message response) {
                    activity.finish();
                }
            };
        final Handler handler = new Handler() {
                public void handleMessage(Message response) {
                    CharSequence newTitle = (CharSequence)response.obj;
                    int fileType = response.arg1;
                    saveRingtone(newTitle, fileType);
                }
            };
        Message message = Message.obtain(handler);
        FileSaveDialog dlog = new FileSaveDialog(
            this, getResources(), mTitle + " Ringtone", message);
        dlog.show();
    }

    private void enableZoomButtons() {
        mZoomInButton.setEnabled(mWaveformView.canZoomIn());
        mZoomOutButton.setEnabled(mWaveformView.canZoomOut());
    }

    private OnClickListener mSaveListener = new OnClickListener() {
            public void onClick(View sender) {
                onSave();
            }
        };

    private OnClickListener mPlayListener = new OnClickListener() {
            public void onClick(View sender) {
                onPlay(mStartPos);
            }
        };

    private OnClickListener mZoomInListener = new OnClickListener() {
            public void onClick(View sender) {
                mWaveformView.zoomIn();
                mStartPos = mWaveformView.getStart();
                mEndPos = mWaveformView.getEnd();
                mMaxPos = mWaveformView.maxPos();
                mOffset = mWaveformView.getOffset();
                mOffsetGoal = mOffset;
                enableZoomButtons();
                updateDisplay();
            }
        };

    private OnClickListener mZoomOutListener = new OnClickListener() {
            public void onClick(View sender) {
                mWaveformView.zoomOut();
                mStartPos = mWaveformView.getStart();
                mEndPos = mWaveformView.getEnd();
                mMaxPos = mWaveformView.maxPos();
                mOffset = mWaveformView.getOffset();
                mOffsetGoal = mOffset;
                enableZoomButtons();
                updateDisplay();
            }
        };

    private OnClickListener mRewindListener = new OnClickListener() {
            public void onClick(View sender) {
                if (mPlayer != null) {
                    int newPos = mPlayer.getCurrentPosition() - 5000;
                    if (newPos < mPlayStartMsec)
                        newPos = mPlayStartMsec;
                    mPlayer.seekTo(newPos);
                } else {
                    mStartMarker.requestFocus();
                    markerFocus(mStartMarker);
                }
            }
        };

    private OnClickListener mFfwdListener = new OnClickListener() {
            public void onClick(View sender) {
                if (mPlayer != null) {
                    int newPos = 5000 + mPlayer.getCurrentPosition();
                    if (newPos > mPlayEndMsec)
                        newPos = mPlayEndMsec;
                    mPlayer.seekTo(newPos);
                } else {
                    mEndMarker.requestFocus();
                    markerFocus(mEndMarker);
                }
            }
        };

    private OnClickListener mMarkStartListener = new OnClickListener() {
            public void onClick(View sender) {
                if (mPlayer != null) {
                    mStartPos = mWaveformView.millisecsToPixels(
                        mPlayer.getCurrentPosition());
                    updateDisplay();
                }
            }
        };

    private OnClickListener mMarkEndListener = new OnClickListener() {
            public void onClick(View sender) {
                if (mPlayer != null) {
                    mEndPos = mWaveformView.millisecsToPixels(
                        mPlayer.getCurrentPosition());
                    updateDisplay();
                    handleStop();
                }
            }
        };

    private TextWatcher mTextWatcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            public void onTextChanged(CharSequence s,
                                      int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                if (mStartText.hasFocus()) {
                    try {
                        mStartPos = mWaveformView.secondsToPixels(
                            Double.parseDouble(
                                mStartText.getText().toString()));
                        updateDisplay();
                    } catch (NumberFormatException e) {
                    }
                }
                if (mEndText.hasFocus()) {
                    try {
                        mEndPos = mWaveformView.secondsToPixels(
                            Double.parseDouble(
                                mEndText.getText().toString()));
                        updateDisplay();
                    } catch (NumberFormatException e) {
                    }
                }
            }
        };

    private String getStackTrace(Exception e) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream, true);
        e.printStackTrace(writer);
        return stream.toString();
    }

    private String getBasename(String filename) {
        return filename.substring(filename.lastIndexOf('/') + 1,
                                  filename.lastIndexOf('.'));
    }

    /**
     * Return extension including dot, like ".mp3"
     */
    private String getExtensionFromFilename(String filename) {
        return filename.substring(filename.lastIndexOf('.'),
                                  filename.length());
    }

    private void getTitleAndArtistFromFilename(String filename) {
        Uri uri = MediaStore.Audio.Media.getContentUriForPath(filename);
        Cursor c = managedQuery(
            uri,
            new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA },
            MediaStore.Audio.Media.DATA + " LIKE \"" + filename + "\"",
            null, null);
        if (c.getCount() == 0) {
            mTitle = getBasename(filename);
            mArtist = null;
            return;
        }
        c.moveToFirst();
        int titleIndex = c.getColumnIndexOrThrow(
            MediaStore.Audio.Media.TITLE);
        String title = c.getString(titleIndex);
        if (title != null && title.length() > 0) {
            mTitle = title;
        } else {
            mTitle = getBasename(filename);
            mArtist = null;
            return;
        }

        int artistIndex = c.getColumnIndexOrThrow(
            MediaStore.Audio.Media.ARTIST);
        String artist = c.getString(artistIndex);
        if (artist != null && artist.length() > 0) {
            mArtist = artist;
        } else {
            mArtist = null;
        }
    }

    private String getFilenameFromUri(Uri uri) {
        Cursor c = managedQuery(uri, null, "", null, null);
        if (c.getCount() == 0) {
            return null;
        }
        c.moveToFirst();
        int dataIndex = c.getColumnIndexOrThrow(
            MediaStore.Audio.Media.DATA);

        return c.getString(dataIndex);
    }
}
