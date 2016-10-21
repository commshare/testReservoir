package io.vec.demo.mediacodec;

import java.nio.ByteBuffer;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
/*
*  20161021 refer to https://dpsm.wordpress.com/2012/07/28/android-mediacodec-decoded/ to make the process more clear ,
*  furthermore , plan to add audio decoder .
* */
public class DecodeActivity extends Activity implements SurfaceHolder.Callback {
	private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/libCGE/video.mp4";
	private PlayerThread mPlayer = null;
	private String T="ZB";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		SurfaceView sv = new SurfaceView(this);
		sv.getHolder().addCallback(this);
		setContentView(sv);
	}

	protected void onDestroy() {
		super.onDestroy();
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.d("ZB","surfaceChanged w["+width+"] height["+height+"]");
		if (mPlayer == null) {
			mPlayer = new PlayerThread(holder.getSurface());
			mPlayer.start();
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		if (mPlayer != null) {
			mPlayer.interrupt();
		}
	}

	private class PlayerThread extends Thread {
		private MediaExtractor extractor;
		private MediaCodec decoder;
		private MediaCodec audioDecoder;
		private Surface surface;

		public PlayerThread(Surface surface) {
			this.surface = surface;
		}

		@Override
		public void run() {
			extractor = new MediaExtractor();
			extractor.setDataSource(SAMPLE);
			Log.d(T,String.format("TRACKS #: %d",extractor.getTrackCount()));
			for (int i = 0; i < extractor.getTrackCount(); i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				Log.d(T, String.format("#%d#MIME TYPE: %s",i, mime));
				if (mime.startsWith("video/")) {/* video/avc*/
					extractor.selectTrack(i);
					decoder = MediaCodec.createDecoderByType(mime);
					decoder.configure(format, surface, null, 0);
				//	break;
				}
				if(mime.startsWith("audio/")){ /*audio/mp4a-latm*/
					Log.d(T, String.format("MIME TYPE: %s", mime));
					extractor.selectTrack(i);
					audioDecoder=MediaCodec.createDecoderByType(mime);
					audioDecoder.configure(format,null/*surface*/,null/*croypto*/,0/*flag*/);
				}
			}

			if (decoder == null) {
				Log.e("DecodeActivity", "Can't find video info!");
				return;
			}
			if(audioDecoder==null){
				Log.e(T,"AUDIO DECODER CREATED FAIL");
				return;
			}
			decoder.start();
			audioDecoder.start();
			/*每一个实例都有一个输入和一个输出缓冲*/
			ByteBuffer[] inputBuffers = decoder.getInputBuffers();
			ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
			BufferInfo info = new BufferInfo();
			boolean sawInputEOS = false;//isEOS
			long presentationTimeUs = 0;
			long startMs = System.currentTimeMillis();
/*对于decoder，input buffer里是编码好的数据，对于encoder，output buffer里编码好的数据*/
			while (!Thread.interrupted()) {
				if (!sawInputEOS) {
					/*====================================decoder的输入端处理 begin=====================================*/
					/*当实例有可用的数据的时候，要把数据传递给codec以解码，调用下面的接口实现实例把input buffer的数据丢给codec*/
					int inIndex = decoder.dequeueInputBuffer(10000);
					if (inIndex >= 0) {
						ByteBuffer dstBuf = inputBuffers[inIndex];/*实例拿codec的未用的输入缓冲*/
						int sampleSize = extractor.readSampleData(dstBuf, 0);/*让extractor放入demux出来的数据*/
						if (sampleSize < 0) {
							sawInputEOS = true;/*extractor拿不到数据就认为EOS了*/
							sampleSize=0;//make it 0 to tell eos
							presentationTimeUs=0;
							// We shouldn't stop the playback at this point, just pass the EOS
							// flag to decoder, we will get it again from the
							// dequeueOutputBuffer
							Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
/*							*//*文档说是通过以下接口，发送带有BUFFER_FLAG_END_OF_STREAM 下标的信号，表示已经EOS了*//*
							decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);*/

						} else {
							presentationTimeUs = extractor.getSampleTime();
						}
						/*跟上面EOS的信号合并为一行代码了*/
						/*填充好未解码数据的input buffer 丢给codec处理，数据大小及时间戳都送入*/
						decoder.queueInputBuffer(inIndex, 0/*offset*/, sampleSize,presentationTimeUs ,sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM: 0);
						if(!sawInputEOS)/*extractor能拿到数据才继续取数据*/
							extractor.advance();
					}
				}//end of if(!sawInputEOS)
     			/*====================================decoder的输入端处理 end=====================================*/
				/*====================================decoder从codec的输出端拉取解码后数据的处理过程 begin=====================================*/
				/*从codec拿outputbuffer到实例*//*这是通过解码器的输出的数据的信息判断是不是EOS了*/
				final int res = decoder.dequeueOutputBuffer(info, 10000);
				switch (res) {
				case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
					Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
					outputBuffers = decoder.getOutputBuffers();
					break;
				case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
					final MediaFormat oformat = decoder.getOutputFormat();
					Log.d(T, "Output format has changed to " + oformat);
					Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
					break;
				case MediaCodec.INFO_TRY_AGAIN_LATER:
					Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
					break;
				default:
					int outputBufIndex = res;
					ByteBuffer buffer = outputBuffers[outputBufIndex];
					Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + buffer);

					// We use a very simple clock to keep the video FPS, or the video
					// playback will be too fast
					while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
						try {
							sleep(10);
						} catch (InterruptedException e) {
							e.printStackTrace();
							break;
						}
					}
					/*实例调用下面接口会把这个outputbuffer还给codec，如果还设置了surface，那么outputbuffer的内容还会显示出来*/
					// 将解码后数据渲染到surface上
					decoder.releaseOutputBuffer(outputBufIndex, true/*render*/);
					break;
				}

				// All decoded frames have been rendered, we can stop playing now
				if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
					break;
				}
			}

			decoder.stop();
			audioDecoder.stop();
			audioDecoder.release();
			decoder.release();
			extractor.release();
		}
	}
}