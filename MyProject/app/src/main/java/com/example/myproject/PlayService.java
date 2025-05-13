/**
 * PlayService - Background music service
 *
 * Handles combat music playback using MediaPlayer.
 * Manages audio lifecycle and MediaPlayer callbacks.
 */
package com.example.myproject;

import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.IBinder;
import androidx.annotation.Nullable;

public class PlayService extends Service implements
        MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnInfoListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnErrorListener {

    private MediaPlayer mediaPlayer;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnBufferingUpdateListener(this);
        mediaPlayer.setOnSeekCompleteListener(this);
        mediaPlayer.setOnInfoListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.reset();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mediaPlayer.reset();

        if (!mediaPlayer.isPlaying()) {
            mediaPlayer = MediaPlayer.create(this, R.raw.combat_music);
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
        }

        return START_STICKY;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (!mp.isPlaying()) {
            mp.setLooping(true);
            mp.start();
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (mp.isPlaying()) {
            mp.stop();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {}

    @Override
    public void onSeekComplete(MediaPlayer mp) {}

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        return false;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        return false;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}