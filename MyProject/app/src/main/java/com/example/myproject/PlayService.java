package com.example.myproject;

import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.IBinder;
import androidx.annotation.Nullable;

/**
 * A Service to handle background audio playback using MediaPlayer.
 * This service manages the lifecycle of audio playback and interfaces with MediaPlayer events.
 *
 * Implements various MediaPlayer listeners to handle playback events:
 * - OnCompletionListener: Triggered when playback completes
 * - OnPreparedListener: Triggered when player is ready for playback
 * - OnSeekCompleteListener: Triggered when seek operation completes
 * - OnInfoListener: Provides informational events during playback
 * - OnBufferingUpdateListener: Provides buffering progress updates
 * - OnErrorListener: Handles errors during playback
 */
public class PlayService extends Service implements
        MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnInfoListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnErrorListener {

    /** The MediaPlayer instance responsible for audio playback */
    private MediaPlayer mediaPlayer;

    /**
     * Called when the service is first created.
     * Initializes the MediaPlayer instance and sets up all necessary listeners.
     */
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

    /**
     * Called each time the service is started with an Intent.
     * Extracts the audio URL from the intent and starts playback preparation.
     *
     * @param intent The Intent supplied to startService, containing the audio URL in "link" extra
     * @param flags Additional data about this start request
     * @param startId A unique integer representing this specific request to start
     * @return START_STICKY indicates that the service should remain running until explicitly stopped
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String link = intent.getStringExtra("link");
        mediaPlayer.reset();

        if (!mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.setDataSource(link);
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return START_STICKY;
    }

    /**
     * Called when the MediaPlayer is prepared and ready for playback.
     * Starts playback if the player is not already playing.
     *
     * @param mp The MediaPlayer instance that is ready for playback
     */
    @Override
    public void onPrepared(MediaPlayer mp) {
        if (!mp.isPlaying()) {
            // Set the player to loop continuously
            mp.setLooping(true);
            mp.start();
        }
    }

    /**
     * Called when audio playback has completed.
     * Stops the player and stops the service itself.
     *
     * @param mp The MediaPlayer instance that completed playback
     */
    @Override
    public void onCompletion(MediaPlayer mp) {
        if (mp.isPlaying()) {
            mp.stop();
        }
        stopSelf();
    }

    /**
     * Called when the service is being destroyed.
     * Releases the MediaPlayer resources to prevent memory leaks.
     */
    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }

    /**
     * Called to provide updates on buffering status during playback.
     * Not actively used in this implementation.
     *
     * @param mp The MediaPlayer instance
     * @param percent Buffer fill percentage
     */
    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        // No action needed in this implementation
    }

    /**
     * Called when a seek operation has been completed.
     * Not actively used in this implementation.
     *
     * @param mp The MediaPlayer instance that completed seeking
     */
    @Override
    public void onSeekComplete(MediaPlayer mp) {
        // No action needed in this implementation
    }

    /**
     * Called when an error has occurred during playback.
     * Returning false allows the OnCompletion listener to be called.
     *
     * @param mp The MediaPlayer instance
     * @param what The type of error that occurred
     * @param extra An extra code specific to the error
     * @return false to allow OnCompletion to be called
     */
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        return false;
    }

    /**
     * Called to indicate an informational event during playback.
     * Not actively used in this implementation.
     *
     * @param mp The MediaPlayer instance
     * @param what The type of info event
     * @param extra An extra code specific to the info message
     * @return false as no custom handling is implemented
     */
    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        return false;
    }

    /**
     * Called when the service is being bound to an activity.
     * Returns null as this is intended to be a started service, not a bound service.
     *
     * @param intent The Intent that was used to bind to this service
     * @return null since binding is not supported
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}