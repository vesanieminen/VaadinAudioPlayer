package com.vaadin.addon.audio.client;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gwt.core.client.Duration;
import com.google.gwt.user.client.Timer;
import com.vaadin.addon.audio.client.ClientStream.DataCallback;
import com.vaadin.addon.audio.client.utils.AudioBufferUtils;
import com.vaadin.addon.audio.client.webaudio.AudioNode;
import com.vaadin.addon.audio.client.webaudio.Buffer;
import com.vaadin.addon.audio.client.webaudio.BufferSourceNode;
import com.vaadin.addon.audio.shared.ChunkDescriptor;
import com.vaadin.addon.audio.shared.util.Log;

/**
 * Player controls for a stream.
 */
public class AudioStreamPlayer {

	// TODO: move audio effects and effects chain processing to separate class
	
	private static Logger logger = Logger.getLogger("AudioStreamPlayer");
	
	private static final int MAX_BUFFER_PLAYERS = 3;	// Maximum number of players
	//TODO: get values from ChunkDescriptions
	private int timePerChunk = 5000;
	private int chunkOverlapTime = 500; // extra time added to end of each chunk

	private int position = 0;
	private int chunkPosition = 0;
	
	private double volume = 1;
	private double playbackSpeed = 1;
	private double balance = 0;
	
	private ClientStream stream;
	private BufferPlayerManager playerManager;
	private Timer playNextChunkTimer;
	private Duration chunkPositionClock;
	
	private List<Effect> effects = new ArrayList<Effect>();

	public AudioStreamPlayer(ClientStream stream) {
		// warm up the stream
		this.stream = stream;
		// playerManager keeps track of the different BufferPlayers
		playerManager = new BufferPlayerManager(MAX_BUFFER_PLAYERS);
		// request first audio chunk
		stream.requestChunkByTimestamp(0, new DataCallback() {
			@Override
			public void onDataReceived(ChunkDescriptor chunk) {
				BufferPlayer player = new BufferPlayer();
				player.setBuffer(AudioStreamPlayer.this.stream.getBufferForChunk(chunk));
				setPersistingPlayerOptions(player);
				playerManager.setCurrentPlayer(player);
				chunkOverlapTime = chunk.getOverlapTime();
				// TODO: shouldn't need to add 1 here
				timePerChunk = chunk.getEndTimeOffset() - chunk.getStartSampleOffset() + 1 - chunkOverlapTime;
				logError("timePerChunk: " + timePerChunk + "\r\n" + "chunkLeadTime: " + chunkOverlapTime);
			}
		});
		// setup timer for moving to next chunk after current chunk finishes playing
		playNextChunkTimer = new Timer() {
			@Override
			public void run() {
				playNextChunk();
			}
		};
	}
	
	public void play() {
		play(true);
	}
	
	public void play(boolean useCrossFade) {
		play(0, useCrossFade);
	}
	
	private void play(int timeOffset, boolean useCrossFade) {
		logError("PLAY");
		if (playerManager.getCurrentPlayer() == null) {
			Log.error(this, "current player is null");
			return;
		}

		chunkPosition = timeOffset;
		int playOffset = ((int) (chunkPosition / playbackSpeed));

		// starts counting MS when instantiated, used primarily for pausing
		chunkPositionClock = new Duration();

		if (useCrossFade) {
			// use cross fade to blend prev and current audio together
			int overlapTime = ((int) (chunkOverlapTime / playbackSpeed));
			AudioBufferUtils.crossFadePlayers(playerManager.getCurrentPlayer(), playerManager.getPrevPlayer(), playOffset, volume, overlapTime);
		} else {
			// simply play the audio
			playerManager.getCurrentPlayer().play(playOffset);
		}
		
		// start timer to play next chunk of audio
		scheduleNextChunk();
		
		// start loading next chunk
		int nextChunkTime = position + timePerChunk + chunkOverlapTime;
		logError("nextChunkTime: " + nextChunkTime);
		stream.requestChunkByTimestamp(nextChunkTime, new DataCallback() {
			@Override
			public void onDataReceived(ChunkDescriptor chunk) {
				final BufferPlayer player = new BufferPlayer();
				player.setBuffer(AudioStreamPlayer.this.stream.getBufferForChunk(chunk), new BufferSourceNode.BufferReadyListener() {
					@Override
					public void onBufferReady(Buffer b) {
						setPersistingPlayerOptions(player);
					}
				});
				playerManager.setNextPlayer(player);
			}
		});
		
	}
	
	public void pause() {
		logError("PAUSE");	
		if (playerManager.getCurrentPlayer() == null) {
			Log.error(this, "current player is null");
			return;
		}
		chunkPosition += chunkPositionClock.elapsedMillis() * playbackSpeed;
		chunkPositionClock = null;
		playerManager.getCurrentPlayer().stop();
		playNextChunkTimer.cancel();
		logError("pause() - elapsedTime:  " + chunkPosition);
	}
	
	public void resume() {
		logError("resume");
		if (playerManager.getCurrentPlayer() == null) {
			Log.error(this, "current player is null");
			return;
		}
		int playOffset = ((int) (chunkPosition / playbackSpeed));
		logError("chunk time position: " + chunkPosition + " / with playbackSpeed: " + playOffset);
		setPersistingPlayerOptions(playerManager.getCurrentPlayer());
		connectBufferPlayerToEffectChain(playerManager.getCurrentPlayer(), effects);
		playerManager.getCurrentPlayer().play(playOffset);
		// schedule next chunk handoff
		scheduleNextChunk();
		// Duration object starts counting MS when instantiated
		chunkPositionClock = new Duration();
	}
	
	public void stop() {
		logError("stop");
		if (playerManager.getCurrentPlayer() == null) {
			Log.error(this, "current player is null");
			return;
		}
		// reset everything
		playerManager.getCurrentPlayer().stop();
		playNextChunkTimer.cancel();
		position = 0;
		chunkPosition = 0;
		chunkPositionClock = null;
		// load the first chunk (beginning of audio)
		stream.requestChunkByTimestamp(0, new DataCallback() {
			@Override
			public void onDataReceived(ChunkDescriptor chunk) {
				final BufferPlayer player = new BufferPlayer();
				player.setBuffer(AudioStreamPlayer.this.stream.getBufferForChunk(chunk), new BufferSourceNode.BufferReadyListener() {
					@Override
					public void onBufferReady(Buffer b) {
						setPersistingPlayerOptions(player);
					}
				});
				AudioStreamPlayer.this.playerManager.setCurrentPlayer(player);
			}
		});
	}
	
	private void scheduleNextChunk() {
		playNextChunkTimer.cancel();
		double chunkDuration = timePerChunk / playbackSpeed;
		double chunkOffset = chunkPosition / playbackSpeed;
		double overlapDuration = chunkOverlapTime / playbackSpeed;
		if (position < chunkDuration) {
			// for some reason the first chunk is fading out 500ms early (or the second chunk is 500ms late)
			// TODO: first chunk should work the same way as others
			// truncating after decimal doesn't matter since we are already in milliseconds
			int time = ((int) (chunkDuration - chunkOffset - overlapDuration));
			if (time < 0) {
				time = 0;
			}
			logError("Scheduling for " + time + " [" + chunkDuration + ", " + chunkOffset + ", " + overlapDuration + "]");
			playNextChunkTimer.schedule(time);
		} else {
			int time = ((int) (chunkDuration - chunkOffset));
			if (time < 0) {
				time = 0;
			}
			logError("Scheduling for " + time + " [" + chunkDuration + ", " + chunkOffset + ", " + overlapDuration + "]");
			playNextChunkTimer.schedule(time);
		}
	}
	
	/**
	 * Gets playtime position of the current audio chunk.
	 * @return playtime position (milliseconds)
	 */
	private int getChunkPosition() {
		int pos = chunkPosition;
		if (chunkPositionClock != null) {
			pos += (chunkPositionClock.elapsedMillis() * playbackSpeed);
		}
		return pos;
	}
	
	private void playNextChunk() {
		logError("PLAY NEXT CHUNK");
		position += timePerChunk;
		// stop the audio if we've reached the end
		if (getPosition() >= getDuration()) {
			stop();
		} else {
			// make sure the next player has the same options set as current
			// setPersistingPlayerOptions(getNextPlayer());
			playerManager.moveToNextPlayer();
			play(true);
		}
	}
	
	private void setPersistingPlayerOptions(BufferPlayer player) {
		if (player == null) {
			Log.error(this, "Cannot copy player options to null BufferPlayer");
			return;
		}
		// copy persisting settings to next audio node
		player.setVolume(volume);
		player.setPlaybackSpeed(playbackSpeed);
		player.setBalance(balance);
		connectBufferPlayerToEffectChain(player, effects);
	}
	
	public int getDuration() {
		return stream.getDuration();
	}
	
	public void setPosition(final int millis) {
		logError("set position to " + millis);
		playerManager.getCurrentPlayer().stop();
		playNextChunkTimer.cancel();
		// calculate the offset time within this audio chunk
		final int offset = millis % timePerChunk;
		position = millis - offset;
		// get audio chunk needed for this time position
		stream.requestChunkByTimestamp(millis, new DataCallback() {
			@Override
			public void onDataReceived(ChunkDescriptor chunk) {
				// create new buffer player
				final BufferPlayer player = new BufferPlayer();
				// request required chunk of audio
				player.setBuffer(AudioStreamPlayer.this.stream.getBufferForChunk(chunk), new BufferSourceNode.BufferReadyListener() {
					@Override
					public void onBufferReady(Buffer b) {
						// setup buffer player and play when ready
						setPersistingPlayerOptions(player);
						AudioStreamPlayer.this.playerManager.setCurrentPlayer(player);
						play(offset, false);
					}
				});
			}
		});
	}
	
	/**
	 * Gets current playtime position of the entire audio file.
	 * @return playtime position (milliseconds)
	 */
	public int getPosition() {
		return position + getChunkPosition();
	}
	
	public void setVolume(double volume) {
		this.volume = volume;
		if(playerManager.getCurrentPlayer() == null) {
			logger.log(Level.SEVERE, "CURRENT PLAYER IS NULL");
			return;
		}
		// TODO: some reason wasn't working when I used getCurrentPlayer().setVolume(volume);
		for (BufferPlayer p : playerManager.getPlayers()) {
			if (p != null) {
				p.setVolume(volume);
			}
		}
	}
	
	public double getVolume() {
		return volume;
	}
	
	public void setPlaybackSpeed(double playbackSpeed) {
		// stop from any division by 0 errors
		if (playbackSpeed <= 0) {
			logError("playback speed must be greater than 0");
			return;
		}
		// save playbackSpeed
		this.playbackSpeed = playbackSpeed;
		// if the current player is not null, apply the speed
		if(playerManager.getCurrentPlayer() == null) {
			logError("current player is null");
			return;
		}
		boolean isPlaying = playerManager.getCurrentPlayer().isPlaying();
		if (isPlaying) {
			pause();
		}
		// update current player so that we have the time warped buffer if needed
		BufferPlayer player = new BufferPlayer();
		player.setBuffer(playerManager.getCurrentPlayer().getBuffer());
		setPersistingPlayerOptions(player);
		playerManager.setCurrentPlayer(player);
		// update next player now to avoid last second processing
		BufferPlayer nextPlayer = new BufferPlayer();
		nextPlayer.setBuffer(playerManager.getNextPlayer().getBuffer());
		setPersistingPlayerOptions(nextPlayer);
		playerManager.setNextPlayer(nextPlayer);
		if (isPlaying) {
			resume();
		}
	}
	
	public double getPlaybackSpeed() {
		return playbackSpeed;
	}
	
	public void setBalance(double balance) {
		this.balance = balance;
		if(playerManager.getCurrentPlayer() == null) {
			logError("current player is null");
			return;
		}
		playerManager.getCurrentPlayer().setBalance(balance);
	}
	
	public double getBalance() {
		return balance;
	}
	
	public void addEffect(Effect effect) {
		logError("AudioStreamPlayer add effect " + effect.getID());
		effects.add(effect);
		connectEffectNodes(effects);
		BufferPlayer currentPlayer = playerManager.getCurrentPlayer();
		if (currentPlayer != null) {
			connectBufferPlayerToEffectChain(currentPlayer, effects);
		}
	}
	
	public void removeEffect(Effect effect) {
		logError("AudioStreamPlayer removing effect " + effect.getID());
		effects.remove(effect);
		connectEffectNodes(effects);
		BufferPlayer currentPlayer = playerManager.getCurrentPlayer();
		if (currentPlayer != null) {
			connectBufferPlayerToEffectChain(currentPlayer, effects);
		}
	}
	
	public void setEffects(List<Effect> effects) {
		logError("AudioStreamPlayer adding effects");
		this.effects.clear();
		if (effects != null) {
			this.effects.addAll(effects);
		}
		connectEffectNodes(effects);
		BufferPlayer currentPlayer = playerManager.getCurrentPlayer();
		if (currentPlayer != null) {
			connectBufferPlayerToEffectChain(currentPlayer, effects);
		}
		
	}
	
	public List<Effect> getEffects() {
		return effects;
	}

	/**
	 * Connects a BufferPlayer to the current set of effects by connecting the
	 * BufferPlayer's source to the effects chain and then outputs to the BufferPlayer's
	 * output node.
	 *
	 * BufferPlayer.source -> [Effect Chain] -> BufferPlayer.output
	 *
	 * @param player
	 * @param effects
	 */
	private void connectBufferPlayerToEffectChain(BufferPlayer player, List<Effect> effects) {
		logger.log(Level.SEVERE, "connecting BufferPlayer source and output to effects chain");
		AudioNode source = player.getSourceNode();
		AudioNode output = player.getOutput();
		source.disconnect();
		source.connect(output);
		if (1 == 1) return;
		// TODO: re-enable (is causing clicks in audio)
		if (effects.size() > 0) {
			AudioNode firstEffect = effects.get(0).getAudioNode();
			AudioNode lastEffect = effects.get(effects.size()-1).getAudioNode();
			logger.log(Level.SEVERE, "connecting source -> first effect: " + firstEffect.toString());
			source.connect(firstEffect);
			lastEffect.connect(output);
		} else {
			logger.log(Level.SEVERE, "connecting source -> output");
			source.connect(output);
		}
	}

	private void disconnectEffectChain(BufferPlayer player, List<Effect> effects) {
		logger.log(Level.SEVERE, "disconnectEffectChain on " + player.toString());
		if (effects.size() > 0) {
			AudioNode firstEffect = effects.get(0).getAudioNode();
			AudioNode lastEffect = effects.get(effects.size()-1).getAudioNode();
			lastEffect.disconnect();
			if (player != null) {
				AudioNode source = player.getSourceNode();
				AudioNode output = player.getOutput();
				source.disconnect(firstEffect);
				lastEffect.disconnect(output);
			}
		}
	}
	
	/**
	 * Goes thru list of effects and connects their corresponding AudioNode's 
	 * to enable us to connect the effects chain in between our source and
	 * destination nodes.
	 */
	private void connectEffectNodes(List<Effect> effects) {
		// TODO: optimize by not completely rebuilding unless neccessary
		logger.log(Level.SEVERE, "connectEffectNodes()");
		String msg = "";
		AudioNode prev = null;
		AudioNode current = null;
		for (Effect effect : effects) {
			prev = current;
			current = effect.getAudioNode();
			// disconnect any previous connections
			if (current != null) {
				current.disconnect();
				msg += current.toString() + " -> ";
				// connect two nodes
				if (prev != null) {
					prev.connect(current);
				}
			}
		}
		logger.log(Level.SEVERE, "Effects node chain: " + msg);
	}

	private static void logError(String msg) {
		logger.log(Level.SEVERE, msg);
	}
	
	public String toString() {
		return "AudioStreamPlayer";
	}
}
