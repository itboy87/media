/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_STEREO_48000KHZ;
import static androidx.media3.transformer.TestUtil.createAudioEffects;
import static androidx.media3.transformer.TestUtil.createChannelCountChangingAudioProcessor;
import static androidx.media3.transformer.TestUtil.createSampleRateChangingAudioProcessor;
import static androidx.media3.transformer.TestUtil.createVolumeScalingAudioProcessor;

import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.test.utils.CapturingAudioSink;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Audio playback unit tests for {@link CompositionPlayer}.
 *
 * <p>These tests focus on audio because the video pipeline doesn't work in Robolectric.
 */
@RunWith(AndroidJUnit4.class)
public final class CompositionPlayerAudioPlaybackTest {

  private final Context context = ApplicationProvider.getApplicationContext();
  private CapturingAudioSink capturingAudioSink;

  @Before
  public void setUp() throws Exception {
    capturingAudioSink = new CapturingAudioSink(new DefaultAudioSink.Builder(context).build());
  }

  @Test
  public void playback_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2);
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context, capturingAudioSink, "audiosinkdumps/wav/sample.wav_then_sample_rf64.wav.dump");
  }

  @Test
  public void playback_compositionWithEffects_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(0.5f)))
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(2f)))
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2);
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sample.wav-lowVolume_then_sample_rf64.wav-highVolume.dump");
  }

  @Test
  public void playback_singleAudioItemWithEffects_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setRemoveVideo(true)
            .setDurationUs(1_000_000L)
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(2f)))
            .build();
    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence(audioEditedMediaItem)).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context, capturingAudioSink, "audiosinkdumps/" + FILE_AUDIO_RAW + "/highVolume.dump");
  }

  @Test
  public void playback_singleAudioItemWithCompositionLevelEffects_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setRemoveVideo(true)
            .setDurationUs(1_000_000L)
            .build();
    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence(audioEditedMediaItem))
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(2f)))
            .build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context, capturingAudioSink, "audiosinkdumps/" + FILE_AUDIO_RAW + "/highVolume.dump");
  }

  @Test
  public void playback_compositionWithClipping_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    MediaItem mediaItem1 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(300)
                    .setEndPositionMs(800)
                    .build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem1).setDurationUs(1_000_000L).build();
    MediaItem mediaItem2 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(100)
                    .setEndPositionMs(300)
                    .build())
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(mediaItem2).setDurationUs(348_000L).build();
    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2))
            .build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sample.wav_clipped_then_sample_rf64_clipped.wav.dump");
  }

  @Test
  public void multiSequenceCompositionPlayback_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionUs(696_000)
                            .build())
                    .build())
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .setEffects(
                createAudioEffects(
                    createSampleRateChangingAudioProcessor(44100),
                    createChannelCountChangingAudioProcessor(1)))
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(editedMediaItem1),
                new EditedMediaItemSequence(editedMediaItem2, editedMediaItem2))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/compositionOf_sample.wav-clipped__sample_rf64.wav.dump");
  }

  @Test
  public void playback_withOneRepeat_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = new EditedMediaItemSequence(editedMediaItem);
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context, capturingAudioSink, "audiosinkdumps/wav/sample.wav_repeated.dump");
  }

  @Test
  public void compositionPlayback_withShortLoopingSequence_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItemSequence primarySequence =
        new EditedMediaItemSequence(
            new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                .setDurationUs(1_000_000L)
                .build());
    EditedMediaItemSequence loopingSequence =
        new EditedMediaItemSequence(
            ImmutableList.of(
                new EditedMediaItem.Builder(
                        MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
                    .setDurationUs(348_000L)
                    .build()),
            /* isLooping= */ true);
    Composition composition = new Composition.Builder(primarySequence, loopingSequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/compositionPlayback_withShortLoopingSequence_outputsCorrectSamples.dump");
  }

  @Test
  public void compositionPlayback_withLongLoopingSequence_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItemSequence primarySequence =
        new EditedMediaItemSequence(
            new EditedMediaItem.Builder(
                    MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
                .setDurationUs(348_000L)
                .build());
    EditedMediaItemSequence loopingSequence =
        new EditedMediaItemSequence(
            ImmutableList.of(
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .build()),
            /* isLooping= */ true);
    Composition composition = new Composition.Builder(primarySequence, loopingSequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/compositionPlayback_withLongLoopingSequence_outputsCorrectSamples.dump");
  }

  @Test
  public void sequencePlayback_withOneRepeat_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2);
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sample.wav_then_sample_rf64.wav_repeated.dump");
  }

  @Test
  public void sequencePlayback_withThreeMediaAndRemovingMiddleAudio_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem audioRemovedMediaItem =
        editedMediaItem.buildUpon().setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(
                    editedMediaItem, audioRemovedMediaItem, editedMediaItem))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // The silence should be in between the timestamp between [1, 2] seconds.
    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sequencePlayback_withThreeMediaAndRemovingMiddleAudio_outputsCorrectSamples.dump");
  }

  @Test
  public void sequencePlayback_withThreeMediaAndRemovingFirstAndThirdAudio_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem audioRemovedMediaItem =
        editedMediaItem.buildUpon().setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(
                    audioRemovedMediaItem, editedMediaItem, audioRemovedMediaItem))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // The silence should be in between the timestamp between [0, 1] and [2, 3] seconds.
    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sequencePlayback_withThreeMediaAndRemovingFirstAndThirdAudio_outputsCorrectSamples.dump");
  }

  // TODO - b/320014878: Enable this test.
  @Ignore("Preview audio is not fed to the sink in deterministic buffers - see b/320014878.")
  @Test
  public void multiSequenceCompositionPlayback_withOneRepeat_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionUs(696_000)
                            .build())
                    .build())
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(editedMediaItem1),
                new EditedMediaItemSequence(editedMediaItem2, editedMediaItem2))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/repeatedCompositionOf_sample.wav-clipped__sample_rf64.wav.dump");
  }

  @Test
  public void seekTo_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = new EditedMediaItemSequence(editedMediaItem);
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 500);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context, capturingAudioSink, "audiosinkdumps/" + FILE_AUDIO_RAW + "/seek_to_500_ms.dump");
  }

  @Test
  public void seekToNextMediaItem_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2);
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 1200);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sample.wav_then_sample_rf64.wav_seek_to_1200_ms.dump");
  }

  @Test
  public void seekToPreviousMediaItem_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2);
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 1200);
    player.seekTo(/* positionMs= */ 500);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sample.wav_then_sample_rf64.wav_seek_to_500_ms.dump");
  }

  @Test
  public void seekTo_withClipping_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    MediaItem mediaItem1 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(200)
                    .setEndPositionMs(900)
                    .build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem1).setDurationUs(1_000_000L).build();
    MediaItem mediaItem2 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(100)
                    .setEndPositionMs(300)
                    .build())
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(mediaItem2).setDurationUs(348_000L).build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence(editedMediaItem1, editedMediaItem2);
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 800);
    player.prepare();
    player.play();
    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        "audiosinkdumps/wav/sample.wav_then_sample_rf64.wav_clipped_seek_to_800_ms.dump");
  }

  private static CompositionPlayer createCompositionPlayer(Context context, AudioSink audioSink) {
    return new CompositionPlayer.Builder(context)
        .setClock(new FakeClock(/* isAutoAdvancing= */ true))
        .setAudioSink(audioSink)
        .build();
  }
}
