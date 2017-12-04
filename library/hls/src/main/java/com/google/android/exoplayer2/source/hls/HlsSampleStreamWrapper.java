/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.source.hls;

import android.os.Handler;
import android.util.Log;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.extractor.DummyTrackOutput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.source.MediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.SampleQueue;
import com.google.android.exoplayer2.source.SampleQueue.UpstreamFormatChangedListener;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.SequenceableLoader;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist.HlsUrl;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Loads {@link HlsMediaChunk}s obtained from a {@link HlsChunkSource}, and provides
 * {@link SampleStream}s from which the loaded media can be consumed.
 */
/* package */ final class HlsSampleStreamWrapper implements Loader.Callback<Chunk>,
    Loader.ReleaseCallback, SequenceableLoader, ExtractorOutput, UpstreamFormatChangedListener {

  /**
   * A callback to be notified of events.
   */
  public interface Callback extends SequenceableLoader.Callback<HlsSampleStreamWrapper> {

    /**
     * Called when the wrapper has been prepared.
     */
    void onPrepared();

    /**
     * Called to schedule a {@link #continueLoading(long)} call when the playlist referred by the
     * given url changes.
     */
    void onPlaylistRefreshRequired(HlsMasterPlaylist.HlsUrl playlistUrl);

  }

  private static final String TAG = "HlsSampleStreamWrapper";

  private static final int PRIMARY_TYPE_NONE = 0;
  private static final int PRIMARY_TYPE_TEXT = 1;
  private static final int PRIMARY_TYPE_AUDIO = 2;
  private static final int PRIMARY_TYPE_VIDEO = 3;

  private final int trackType;
  private final Callback callback;
  private final HlsChunkSource chunkSource;
  private final Allocator allocator;
  private final Format muxedAudioFormat;
  private final int minLoadableRetryCount;
  private final Loader loader;
  private final EventDispatcher eventDispatcher;
  private final HlsChunkSource.HlsChunkHolder nextChunkHolder;
  private final ArrayList<HlsMediaChunk> mediaChunks;
  private final Runnable maybeFinishPrepareRunnable;
  private final Handler handler;

  private SampleQueue[] sampleQueues;
  private int[] sampleQueueTrackIds;
  private boolean sampleQueuesBuilt;
  private boolean prepared;
  private int enabledSampleQueueCount;
  private Format downstreamTrackFormat;
  private boolean released;

  // Tracks are complicated in HLS. See documentation of buildTracks for details.
  // Indexed by track (as exposed by this source).
  private TrackGroupArray trackGroups;
  // Indexed by track group.
  private int[] trackGroupToSampleQueueIndex;
  private int primaryTrackGroupIndex;
  private boolean haveAudioVideoSampleQueues;
  private boolean[] sampleQueuesEnabledStates;
  private boolean[] sampleQueueIsAudioVideoFlags;

  private long sampleOffsetUs;
  private long lastSeekPositionUs;
  private long pendingResetPositionUs;
  private boolean pendingResetUpstreamFormats;
  private boolean seenFirstTrackSelection;
  private boolean loadingFinished;

  /**
   * @param trackType The type of the track. One of the {@link C} {@code TRACK_TYPE_*} constants.
   * @param callback A callback for the wrapper.
   * @param chunkSource A {@link HlsChunkSource} from which chunks to load are obtained.
   * @param allocator An {@link Allocator} from which to obtain media buffer allocations.
   * @param positionUs The position from which to start loading media.
   * @param muxedAudioFormat Optional muxed audio {@link Format} as defined by the master playlist.
   * @param minLoadableRetryCount The minimum number of times that the source should retry a load
   *     before propagating an error.
   * @param eventDispatcher A dispatcher to notify of events.
   */
  public HlsSampleStreamWrapper(int trackType, Callback callback, HlsChunkSource chunkSource,
      Allocator allocator, long positionUs, Format muxedAudioFormat, int minLoadableRetryCount,
      EventDispatcher eventDispatcher) {
    this.trackType = trackType;
    this.callback = callback;
    this.chunkSource = chunkSource;
    this.allocator = allocator;
    this.muxedAudioFormat = muxedAudioFormat;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventDispatcher = eventDispatcher;
    loader = new Loader("Loader:HlsSampleStreamWrapper");
    nextChunkHolder = new HlsChunkSource.HlsChunkHolder();
    sampleQueueTrackIds = new int[0];
    sampleQueues = new SampleQueue[0];
    sampleQueueIsAudioVideoFlags = new boolean[0];
    sampleQueuesEnabledStates = new boolean[0];
    mediaChunks = new ArrayList<>();
    maybeFinishPrepareRunnable = new Runnable() {
      @Override
      public void run() {
        maybeFinishPrepare();
      }
    };
    handler = new Handler();
    lastSeekPositionUs = positionUs;
    pendingResetPositionUs = positionUs;
  }

  public void continuePreparing() {
    if (!prepared) {
      continueLoading(lastSeekPositionUs);
    }
  }

  /**
   * Prepares a sample stream wrapper for which the master playlist provides enough information to
   * prepare.
   */
  public void prepareSingleTrack(Format format) {
    track(0, C.TRACK_TYPE_UNKNOWN).format(format);
    sampleQueuesBuilt = true;
    maybeFinishPrepare();
  }

  public void maybeThrowPrepareError() throws IOException {
    maybeThrowError();
  }

  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  /**
   * Called by the parent {@link HlsMediaPeriod} when a track selection occurs.
   *
   * @param selections The renderer track selections.
   * @param mayRetainStreamFlags Flags indicating whether the existing sample stream can be retained
   *     for each selection. A {@code true} value indicates that the selection is unchanged, and
   *     that the caller does not require that the sample stream be recreated.
   * @param streams The existing sample streams, which will be updated to reflect the provided
   *     selections.
   * @param streamResetFlags Will be updated to indicate new sample streams, and sample streams that
   *     have been retained but with the requirement that the consuming renderer be reset.
   * @param positionUs The current playback position in microseconds.
   * @param forceReset If true then a reset is forced (i.e. a seek will be performed with in-buffer
   *     seeking disabled).
   * @return Whether this wrapper requires the parent {@link HlsMediaPeriod} to perform a seek as
   *     part of the track selection.
   */
  public boolean selectTracks(TrackSelection[] selections, boolean[] mayRetainStreamFlags,
      SampleStream[] streams, boolean[] streamResetFlags, long positionUs, boolean forceReset) {
    Assertions.checkState(prepared);
    int oldEnabledSampleQueueCount = enabledSampleQueueCount;
    // Deselect old tracks.
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
        setSampleQueueEnabledState(((HlsSampleStream) streams[i]).sampleQueueIndex, false);
        streams[i] = null;
      }
    }
    // We'll always need to seek if we're being forced to reset, or if this is a first selection to
    // a position other than the one we started preparing with, or if we're making a selection
    // having previously disabled all tracks.
    boolean seekRequired = forceReset
        || (seenFirstTrackSelection ? oldEnabledSampleQueueCount == 0
            : positionUs != lastSeekPositionUs);
    // Get the old (i.e. current before the loop below executes) primary track selection. The new
    // primary selection will equal the old one unless it's changed in the loop.
    TrackSelection oldPrimaryTrackSelection = chunkSource.getTrackSelection();
    TrackSelection primaryTrackSelection = oldPrimaryTrackSelection;
    // Select new tracks.
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] == null && selections[i] != null) {
        TrackSelection selection = selections[i];
        int trackGroupIndex = trackGroups.indexOf(selection.getTrackGroup());
        int sampleQueueIndex = trackGroupToSampleQueueIndex[trackGroupIndex];
        setSampleQueueEnabledState(sampleQueueIndex, true);
        if (trackGroupIndex == primaryTrackGroupIndex) {
          primaryTrackSelection = selection;
          chunkSource.selectTracks(selection);
        }
        streams[i] = new HlsSampleStream(this, sampleQueueIndex);
        streamResetFlags[i] = true;
        // If there's still a chance of avoiding a seek, try and seek within the sample queue.
        if (!seekRequired) {
          SampleQueue sampleQueue = sampleQueues[sampleQueueIndex];
          sampleQueue.rewind();
          // A seek can be avoided if we're able to advance to the current playback position in the
          // sample queue, or if we haven't read anything from the queue since the previous seek
          // (this case is common for sparse tracks such as metadata tracks). In all other cases a
          // seek is required.
          seekRequired = sampleQueue.advanceTo(positionUs, true, true) == SampleQueue.ADVANCE_FAILED
              && sampleQueue.getReadIndex() != 0;
        }
      }
    }

    if (enabledSampleQueueCount == 0) {
      chunkSource.reset();
      downstreamTrackFormat = null;
      mediaChunks.clear();
      if (loader.isLoading()) {
        // Discard as much as we can synchronously.
        for (SampleQueue sampleQueue : sampleQueues) {
          sampleQueue.discardToEnd();
        }
        loader.cancelLoading();
      } else {
        resetSampleQueues();
      }
    } else {
      if (!mediaChunks.isEmpty()
          && !Util.areEqual(primaryTrackSelection, oldPrimaryTrackSelection)) {
        // The primary track selection has changed and we have buffered media. The buffered media
        // may need to be discarded.
        boolean primarySampleQueueDirty = false;
        if (!seenFirstTrackSelection) {
          long bufferedDurationUs = positionUs < 0 ? -positionUs : 0;
          primaryTrackSelection.updateSelectedTrack(positionUs, bufferedDurationUs, C.TIME_UNSET);
          int chunkIndex = chunkSource.getTrackGroup().indexOf(getLastMediaChunk().trackFormat);
          if (primaryTrackSelection.getSelectedIndexInTrackGroup() != chunkIndex) {
            // This is the first selection and the chunk loaded during preparation does not match
            // the initially selected format.
            primarySampleQueueDirty = true;
          }
        } else {
          // The primary sample queue contains media buffered for the old primary track selection.
          primarySampleQueueDirty = true;
        }
        if (primarySampleQueueDirty) {
          forceReset = true;
          seekRequired = true;
          pendingResetUpstreamFormats = true;
        }
      }
      if (seekRequired) {
        seekToUs(positionUs, forceReset);
        // We'll need to reset renderers consuming from all streams due to the seek.
        for (int i = 0; i < streams.length; i++) {
          if (streams[i] != null) {
            streamResetFlags[i] = true;
          }
        }
      }
    }

    seenFirstTrackSelection = true;
    return seekRequired;
  }

  public void discardBuffer(long positionUs, boolean toKeyframe) {
    int sampleQueueCount = sampleQueues.length;
    for (int i = 0; i < sampleQueueCount; i++) {
      sampleQueues[i].discardTo(positionUs, toKeyframe, sampleQueuesEnabledStates[i]);
    }
  }

  /**
   * Attempts to seek to the specified position in microseconds.
   *
   * @param positionUs The seek position in microseconds.
   * @param forceReset If true then a reset is forced (i.e. in-buffer seeking is disabled).
   * @return Whether the wrapper was reset, meaning the wrapped sample queues were reset. If false,
   *     an in-buffer seek was performed.
   */
  public boolean seekToUs(long positionUs, boolean forceReset) {
    lastSeekPositionUs = positionUs;
    // If we're not forced to reset nor have a pending reset, see if we can seek within the buffer.
    if (!forceReset && !isPendingReset() && seekInsideBufferUs(positionUs)) {
      return false;
    }
    // We were unable to seek within the buffer, so need to reset.
    pendingResetPositionUs = positionUs;
    loadingFinished = false;
    mediaChunks.clear();
    if (loader.isLoading()) {
      loader.cancelLoading();
    } else {
      resetSampleQueues();
    }
    return true;
  }

  public void release() {
    boolean releasedSynchronously = loader.release(this);
    if (prepared && !releasedSynchronously) {
      // Discard as much as we can synchronously. We only do this if we're prepared, since otherwise
      // sampleQueues may still be being modified by the loading thread.
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.discardToEnd();
      }
    }
    handler.removeCallbacksAndMessages(null);
    released = true;
  }

  @Override
  public void onLoaderReleased() {
    resetSampleQueues();
  }

  public void setIsTimestampMaster(boolean isTimestampMaster) {
    chunkSource.setIsTimestampMaster(isTimestampMaster);
  }

  public void onPlaylistBlacklisted(HlsUrl url, long blacklistMs) {
    chunkSource.onPlaylistBlacklisted(url, blacklistMs);
  }

  // SampleStream implementation.

  public boolean isReady(int sampleQueueIndex) {
    return loadingFinished || (!isPendingReset() && sampleQueues[sampleQueueIndex].hasNextSample());
  }

  public void maybeThrowError() throws IOException {
    loader.maybeThrowError();
    chunkSource.maybeThrowError();
  }

  public int readData(int sampleQueueIndex, FormatHolder formatHolder, DecoderInputBuffer buffer,
      boolean requireFormat) {
    if (isPendingReset()) {
      return C.RESULT_NOTHING_READ;
    }

    // TODO: Split into discard (in discardBuffer) and format change (here and in skipData) steps.
    if (!mediaChunks.isEmpty()) {
      int discardToMediaChunkIndex = 0;
      while (discardToMediaChunkIndex < mediaChunks.size() - 1
          && finishedReadingChunk(mediaChunks.get(discardToMediaChunkIndex))) {
        discardToMediaChunkIndex++;
      }
      if (discardToMediaChunkIndex > 0) {
        Util.removeRange(mediaChunks, 0, discardToMediaChunkIndex);
      }
      HlsMediaChunk currentChunk = mediaChunks.get(0);
      Format trackFormat = currentChunk.trackFormat;
      if (!trackFormat.equals(downstreamTrackFormat)) {
        eventDispatcher.downstreamFormatChanged(trackType, trackFormat,
            currentChunk.trackSelectionReason, currentChunk.trackSelectionData,
            currentChunk.startTimeUs);
      }
      downstreamTrackFormat = trackFormat;
    }

    return sampleQueues[sampleQueueIndex].read(formatHolder, buffer, requireFormat, loadingFinished,
        lastSeekPositionUs);
  }

  public int skipData(int sampleQueueIndex, long positionUs) {
    if (isPendingReset()) {
      return 0;
    }

    SampleQueue sampleQueue = sampleQueues[sampleQueueIndex];
    if (loadingFinished && positionUs > sampleQueue.getLargestQueuedTimestampUs()) {
      return sampleQueue.advanceToEnd();
    } else {
      int skipCount = sampleQueue.advanceTo(positionUs, true, true);
      return skipCount == SampleQueue.ADVANCE_FAILED ? 0 : skipCount;
    }
  }

  // SequenceableLoader implementation

  @Override
  public long getBufferedPositionUs() {
    if (loadingFinished) {
      return C.TIME_END_OF_SOURCE;
    } else if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      long bufferedPositionUs = lastSeekPositionUs;
      HlsMediaChunk lastMediaChunk = getLastMediaChunk();
      HlsMediaChunk lastCompletedMediaChunk = lastMediaChunk.isLoadCompleted() ? lastMediaChunk
          : mediaChunks.size() > 1 ? mediaChunks.get(mediaChunks.size() - 2) : null;
      if (lastCompletedMediaChunk != null) {
        bufferedPositionUs = Math.max(bufferedPositionUs, lastCompletedMediaChunk.endTimeUs);
      }
      for (SampleQueue sampleQueue : sampleQueues) {
        bufferedPositionUs = Math.max(bufferedPositionUs,
            sampleQueue.getLargestQueuedTimestampUs());
      }
      return bufferedPositionUs;
    }
  }

  @Override
  public long getNextLoadPositionUs() {
    if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      return loadingFinished ? C.TIME_END_OF_SOURCE : getLastMediaChunk().endTimeUs;
    }
  }

  @Override
  public boolean continueLoading(long positionUs) {
    if (loadingFinished || loader.isLoading()) {
      return false;
    }

    HlsMediaChunk previousChunk;
    long loadPositionUs;
    if (isPendingReset()) {
      previousChunk = null;
      loadPositionUs = pendingResetPositionUs;
    } else {
      previousChunk = getLastMediaChunk();
      loadPositionUs = previousChunk.endTimeUs;
    }
    chunkSource.getNextChunk(previousChunk, positionUs, loadPositionUs, nextChunkHolder);
    boolean endOfStream = nextChunkHolder.endOfStream;
    Chunk loadable = nextChunkHolder.chunk;
    HlsMasterPlaylist.HlsUrl playlistToLoad = nextChunkHolder.playlist;
    nextChunkHolder.clear();

    if (endOfStream) {
      pendingResetPositionUs = C.TIME_UNSET;
      loadingFinished = true;
      return true;
    }

    if (loadable == null) {
      if (playlistToLoad != null) {
        callback.onPlaylistRefreshRequired(playlistToLoad);
      }
      return false;
    }

    if (isMediaChunk(loadable)) {
      pendingResetPositionUs = C.TIME_UNSET;
      HlsMediaChunk mediaChunk = (HlsMediaChunk) loadable;
      mediaChunk.init(this);
      mediaChunks.add(mediaChunk);
    }
    long elapsedRealtimeMs = loader.startLoading(loadable, this, minLoadableRetryCount);
    eventDispatcher.loadStarted(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs);
    return true;
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs) {
    chunkSource.onChunkLoadCompleted(loadable);
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs, loadDurationMs, loadable.bytesLoaded());
    if (!prepared) {
      continueLoading(lastSeekPositionUs);
    } else {
      callback.onContinueLoadingRequested(this);
    }
  }

  @Override
  public void onLoadCanceled(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs,
      boolean released) {
    eventDispatcher.loadCanceled(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs, loadDurationMs, loadable.bytesLoaded());
    if (!released) {
      resetSampleQueues();
      if (enabledSampleQueueCount > 0) {
        callback.onContinueLoadingRequested(this);
      }
    }
  }

  @Override
  public int onLoadError(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs,
      IOException error) {
    long bytesLoaded = loadable.bytesLoaded();
    boolean isMediaChunk = isMediaChunk(loadable);
    boolean cancelable = !isMediaChunk || bytesLoaded == 0;
    boolean canceled = false;
    if (chunkSource.onChunkLoadError(loadable, cancelable, error)) {
      if (isMediaChunk) {
        HlsMediaChunk removed = mediaChunks.remove(mediaChunks.size() - 1);
        Assertions.checkState(removed == loadable);
        if (mediaChunks.isEmpty()) {
          pendingResetPositionUs = lastSeekPositionUs;
        }
      }
      canceled = true;
    }
    eventDispatcher.loadError(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs, loadDurationMs, loadable.bytesLoaded(), error,
        canceled);
    if (canceled) {
      if (!prepared) {
        continueLoading(lastSeekPositionUs);
      } else {
        callback.onContinueLoadingRequested(this);
      }
      return Loader.DONT_RETRY;
    } else {
      return Loader.RETRY;
    }
  }

  // Called by the consuming thread, but only when there is no loading thread.

  /**
   * Initializes the wrapper for loading a chunk.
   *
   * @param chunkUid The chunk's uid.
   * @param shouldSpliceIn Whether the samples parsed from the chunk should be spliced into any
   *     samples already queued to the wrapper.
   */
  public void init(int chunkUid, boolean shouldSpliceIn) {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.sourceId(chunkUid);
    }
    if (shouldSpliceIn) {
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.splice();
      }
    }
  }

  // ExtractorOutput implementation. Called by the loading thread.

  @Override
  public TrackOutput track(int id, int type) {
    int trackCount = sampleQueues.length;
    for (int i = 0; i < trackCount; i++) {
      if (sampleQueueTrackIds[i] == id) {
        return sampleQueues[i];
      }
    }
    if (sampleQueuesBuilt) {
      Log.w(TAG, "Unmapped track with id " + id + " of type " + type);
      return new DummyTrackOutput();
    }
    SampleQueue trackOutput = new SampleQueue(allocator);
    trackOutput.setSampleOffsetUs(sampleOffsetUs);
    trackOutput.setUpstreamFormatChangeListener(this);
    sampleQueueTrackIds = Arrays.copyOf(sampleQueueTrackIds, trackCount + 1);
    sampleQueueTrackIds[trackCount] = id;
    sampleQueues = Arrays.copyOf(sampleQueues, trackCount + 1);
    sampleQueues[trackCount] = trackOutput;
    sampleQueueIsAudioVideoFlags = Arrays.copyOf(sampleQueueIsAudioVideoFlags, trackCount + 1);
    sampleQueueIsAudioVideoFlags[trackCount] = type == C.TRACK_TYPE_AUDIO
        || type == C.TRACK_TYPE_VIDEO;
    haveAudioVideoSampleQueues |= sampleQueueIsAudioVideoFlags[trackCount];
    sampleQueuesEnabledStates = Arrays.copyOf(sampleQueuesEnabledStates, trackCount + 1);
    return trackOutput;
  }

  @Override
  public void endTracks() {
    sampleQueuesBuilt = true;
    handler.post(maybeFinishPrepareRunnable);
  }

  @Override
  public void seekMap(SeekMap seekMap) {
    // Do nothing.
  }

  // UpstreamFormatChangedListener implementation. Called by the loading thread.

  @Override
  public void onUpstreamFormatChanged(Format format) {
    if (!prepared) {
      handler.post(maybeFinishPrepareRunnable);
    }
  }

  // Called by the loading thread.

  public void setSampleOffsetUs(long sampleOffsetUs) {
    this.sampleOffsetUs = sampleOffsetUs;
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    }
  }

  // Internal methods.

  private boolean finishedReadingChunk(HlsMediaChunk chunk) {
    int chunkUid = chunk.uid;
    int sampleQueueCount = sampleQueues.length;
    for (int i = 0; i < sampleQueueCount; i++) {
      if (sampleQueuesEnabledStates[i] && sampleQueues[i].peekSourceId() == chunkUid) {
        return false;
      }
    }
    return true;
  }

  private void resetSampleQueues() {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.reset(pendingResetUpstreamFormats);
    }
    pendingResetUpstreamFormats = false;
  }

  private void maybeFinishPrepare() {
    if (released || prepared || !sampleQueuesBuilt) {
      return;
    }
    for (SampleQueue sampleQueue : sampleQueues) {
      if (sampleQueue.getUpstreamFormat() == null) {
        return;
      }
    }
    buildTracks();
    prepared = true;
    callback.onPrepared();
  }

  /**
   * Builds tracks that are exposed by this {@link HlsSampleStreamWrapper} instance, as well as
   * internal data-structures required for operation.
   * <p>
   * Tracks in HLS are complicated. A HLS master playlist contains a number of "variants". Each
   * variant stream typically contains muxed video, audio and (possibly) additional audio, metadata
   * and caption tracks. We wish to allow the user to select between an adaptive track that spans
   * all variants, as well as each individual variant. If multiple audio tracks are present within
   * each variant then we wish to allow the user to select between those also.
   * <p>
   * To do this, tracks are constructed as follows. The {@link HlsChunkSource} exposes (N+1) tracks,
   * where N is the number of variants defined in the HLS master playlist. These consist of one
   * adaptive track defined to span all variants and a track for each individual variant. The
   * adaptive track is initially selected. The extractor is then prepared to discover the tracks
   * inside of each variant stream. The two sets of tracks are then combined by this method to
   * create a third set, which is the set exposed by this {@link HlsSampleStreamWrapper}:
   * <ul>
   * <li>The extractor tracks are inspected to infer a "primary" track type. If a video track is
   * present then it is always the primary type. If not, audio is the primary type if present.
   * Else text is the primary type if present. Else there is no primary type.</li>
   * <li>If there is exactly one extractor track of the primary type, it's expanded into (N+1)
   * exposed tracks, all of which correspond to the primary extractor track and each of which
   * corresponds to a different chunk source track. Selecting one of these tracks has the effect
   * of switching the selected track on the chunk source.</li>
   * <li>All other extractor tracks are exposed directly. Selecting one of these tracks has the
   * effect of selecting an extractor track, leaving the selected track on the chunk source
   * unchanged.</li>
   * </ul>
   */
  private void buildTracks() {
    // Iterate through the extractor tracks to discover the "primary" track type, and the index
    // of the single track of this type.
    int primaryExtractorTrackType = PRIMARY_TYPE_NONE;
    int primaryExtractorTrackIndex = C.INDEX_UNSET;
    int extractorTrackCount = sampleQueues.length;
    for (int i = 0; i < extractorTrackCount; i++) {
      String sampleMimeType = sampleQueues[i].getUpstreamFormat().sampleMimeType;
      int trackType;
      if (MimeTypes.isVideo(sampleMimeType)) {
        trackType = PRIMARY_TYPE_VIDEO;
      } else if (MimeTypes.isAudio(sampleMimeType)) {
        trackType = PRIMARY_TYPE_AUDIO;
      } else if (MimeTypes.isText(sampleMimeType)) {
        trackType = PRIMARY_TYPE_TEXT;
      } else {
        trackType = PRIMARY_TYPE_NONE;
      }
      if (trackType > primaryExtractorTrackType) {
        primaryExtractorTrackType = trackType;
        primaryExtractorTrackIndex = i;
      } else if (trackType == primaryExtractorTrackType
          && primaryExtractorTrackIndex != C.INDEX_UNSET) {
        // We have multiple tracks of the primary type. We only want an index if there only exists a
        // single track of the primary type, so unset the index again.
        primaryExtractorTrackIndex = C.INDEX_UNSET;
      }
    }

    TrackGroup chunkSourceTrackGroup = chunkSource.getTrackGroup();
    int chunkSourceTrackCount = chunkSourceTrackGroup.length;

    // Instantiate the necessary internal data-structures.
    primaryTrackGroupIndex = C.INDEX_UNSET;
    trackGroupToSampleQueueIndex = new int[extractorTrackCount];
    for (int i = 0; i < extractorTrackCount; i++) {
      trackGroupToSampleQueueIndex[i] = i;
    }

    // Construct the set of exposed track groups.
    TrackGroup[] trackGroups = new TrackGroup[extractorTrackCount];
    for (int i = 0; i < extractorTrackCount; i++) {
      Format sampleFormat = sampleQueues[i].getUpstreamFormat();
      if (i == primaryExtractorTrackIndex) {
        Format[] formats = new Format[chunkSourceTrackCount];
        for (int j = 0; j < chunkSourceTrackCount; j++) {
          formats[j] = deriveFormat(chunkSourceTrackGroup.getFormat(j), sampleFormat);
        }
        trackGroups[i] = new TrackGroup(formats);
        primaryTrackGroupIndex = i;
      } else {
        Format trackFormat = primaryExtractorTrackType == PRIMARY_TYPE_VIDEO
            && MimeTypes.isAudio(sampleFormat.sampleMimeType) ? muxedAudioFormat : null;
        trackGroups[i] = new TrackGroup(deriveFormat(trackFormat, sampleFormat));
      }
    }
    this.trackGroups = new TrackGroupArray(trackGroups);
  }

  /**
   * Enables or disables a specified sample queue.
   *
   * @param sampleQueueIndex The index of the sample queue.
   * @param enabledState True if the sample queue is being enabled, or false if it's being disabled.
   */
  private void setSampleQueueEnabledState(int sampleQueueIndex, boolean enabledState) {
    Assertions.checkState(sampleQueuesEnabledStates[sampleQueueIndex] != enabledState);
    sampleQueuesEnabledStates[sampleQueueIndex] = enabledState;
    enabledSampleQueueCount = enabledSampleQueueCount + (enabledState ? 1 : -1);
  }

  private HlsMediaChunk getLastMediaChunk() {
    return mediaChunks.get(mediaChunks.size() - 1);
  }

  private boolean isPendingReset() {
    return pendingResetPositionUs != C.TIME_UNSET;
  }

  /**
   * Attempts to seek to the specified position within the sample queues.
   *
   * @param positionUs The seek position in microseconds.
   * @return Whether the in-buffer seek was successful.
   */
  private boolean seekInsideBufferUs(long positionUs) {
    int sampleQueueCount = sampleQueues.length;
    for (int i = 0; i < sampleQueueCount; i++) {
      SampleQueue sampleQueue = sampleQueues[i];
      sampleQueue.rewind();
      boolean seekInsideQueue = sampleQueue.advanceTo(positionUs, true, false)
          != SampleQueue.ADVANCE_FAILED;
      // If we have AV tracks then an in-queue seek is successful if the seek into every AV queue
      // is successful. We ignore whether seeks within non-AV queues are successful in this case, as
      // they may be sparse or poorly interleaved. If we only have non-AV tracks then a seek is
      // successful only if the seek into every queue succeeds.
      if (!seekInsideQueue && (sampleQueueIsAudioVideoFlags[i] || !haveAudioVideoSampleQueues)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Derives a track format corresponding to a given container format, by combining it with sample
   * level information obtained from the samples.
   *
   * @param containerFormat The container format for which the track format should be derived.
   * @param sampleFormat A sample format from which to obtain sample level information.
   * @return The derived track format.
   */
  private static Format deriveFormat(Format containerFormat, Format sampleFormat) {
    if (containerFormat == null) {
      return sampleFormat;
    }
    int sampleTrackType = MimeTypes.getTrackType(sampleFormat.sampleMimeType);
    String codecs = Util.getCodecsOfType(containerFormat.codecs, sampleTrackType);
    return sampleFormat.copyWithContainerInfo(containerFormat.id, codecs, containerFormat.bitrate,
        containerFormat.width, containerFormat.height, containerFormat.selectionFlags,
        containerFormat.language);
  }

  private static boolean isMediaChunk(Chunk chunk) {
    return chunk instanceof HlsMediaChunk;
  }

}
