package com.audio.audiorecoder;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class AudioRecordAndPlayActivity extends Activity {
	private static final String TAG = "AudioRecordAndPlayActivity";
	private static final int SAMPLE_RATE = 44100;
	private static final int QUEUE_SIZE = 16;

	private AudioRecord mAudioRecord;
	private AudioTrack mAudioTrack;
	private AcousticEchoCanceler mAEC;
	private ArrayBlockingQueue<byte[]> mAudioDataQueue;
	private byte[] mAudioInBuffer;
	private int mAudioInBufferSize;
	private byte[] mAudioOutBuffer;
	private int mAudioOutBufferSize;
	private boolean mRecordAndPlay = false;
	private boolean mRecordAndPlayReserved = false;
	private volatile boolean mRecordThreadStop = false;
	private volatile boolean mPlayThreadStop = false;
	private Thread mRecordSoundThread;
	private Thread mPlaySoundThread;
	private Button mButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.i(TAG, "OnCreate");
		setContentView(R.layout.activity_audio_recoder);
		this.setTitle(TAG);
		init();
		Log.i(TAG, "OnCreate init done");

		mRecordSoundThread = new Thread(new RecordSound());
		mPlaySoundThread = new Thread(new PlaySound());
		mRecordSoundThread.start();
		mPlaySoundThread.start();
	}

	@Override
	protected void onPause() {
		super.onPause();
		Log.i(TAG, "onPause");
		mRecordAndPlayReserved = mRecordAndPlay;
		mRecordAndPlay = false;
	}

	@Override
	protected void onResume() {
		super.onResume();
		Log.i(TAG, "onResume");
		mRecordAndPlay = mRecordAndPlayReserved;
	}

	@Override
	protected void onStop() {
		super.onStop();
		mRecordThreadStop = true;
		mPlayThreadStop = true;
		Log.i(TAG, "onStop");
		if (mAudioRecord != null) {
			mAudioRecord.stop();
			mAudioRecord.release();
			mAudioRecord = null;
		}
		if (mAudioTrack != null) {
			mAudioTrack.stop();
			mAudioTrack.release();
			mAudioTrack = null;
		}
		if (mAEC != null) {
			mAEC.setEnabled(false);
			mAEC.release();
			mAEC = null;
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.i(TAG, "onStart");
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		Log.i(TAG, "onDestroy");

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.audio_recoder, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void init() {
		mButton = (Button) findViewById(R.id.button_recoder);
		mButton.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				if (mRecordAndPlay == false) {
					mRecordAndPlay = true;
					Toast.makeText(AudioRecordAndPlayActivity.this,
							"Start record and play", Toast.LENGTH_SHORT).show();
					mButton.setText("Stop");
				} else {
					mRecordAndPlay = false;
					Toast.makeText(AudioRecordAndPlayActivity.this,
							"Stop record and play", Toast.LENGTH_SHORT).show();
					mButton.setText("Start");
				}
			}
		});

		mAudioInBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
				AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		mAudioInBuffer = new byte[mAudioInBufferSize];
		if (android.os.Build.VERSION.SDK_INT >= 16) {
			mAudioRecord = new AudioRecord(
					MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, mAudioInBufferSize);
			if (AcousticEchoCanceler.isAvailable()) {
				mAEC = AcousticEchoCanceler.create(mAudioRecord
						.getAudioSessionId());
				try {
					mAEC.setEnabled(true);
				} catch (IllegalStateException e) {
					e.printStackTrace();
				}
			}
		} else {
			mAudioRecord = new AudioRecord(
					MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, mAudioInBufferSize);
		}

		mAudioOutBufferSize = AudioTrack.getMinBufferSize(44100,
				AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		mAudioOutBuffer = new byte[mAudioOutBufferSize];
		if (android.os.Build.VERSION.SDK_INT >= 16) {
			mAudioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, 44100,
					AudioFormat.CHANNEL_OUT_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, mAudioOutBufferSize,
					AudioTrack.MODE_STREAM, mAudioRecord.getAudioSessionId());
		} else {
			mAudioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, 44100,
					AudioFormat.CHANNEL_OUT_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, mAudioOutBufferSize,
					AudioTrack.MODE_STREAM);
		}

		mAudioDataQueue = new ArrayBlockingQueue<byte[]>(QUEUE_SIZE);
	}

	private class RecordSound implements Runnable {
		@Override
		public void run() {
			mAudioRecord.startRecording();
			byte[] tmpBuffer;
			while (!mRecordThreadStop) {
				if (mRecordAndPlay == true) {
					if (mAudioRecord != null) {
						mAudioRecord
								.read(mAudioInBuffer, 0, mAudioInBufferSize);
						tmpBuffer = mAudioInBuffer.clone();
						Log.d(TAG, "Record data length" + tmpBuffer.length);
						try {
							mAudioDataQueue.put(tmpBuffer);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
	}

	private class PlaySound implements Runnable {
		@Override
		public void run() {
			mAudioTrack.play();
			byte[] tmpBuffer;
			while (!mPlayThreadStop) {
				if (mRecordAndPlay == true) {
					if (mAudioTrack != null) {
						try {
							mAudioOutBuffer = mAudioDataQueue.take();
							tmpBuffer = mAudioOutBuffer.clone();
							Log.d(TAG, "Play data length" + tmpBuffer.length);
							mAudioTrack.write(tmpBuffer, 0, tmpBuffer.length);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
	}
}
