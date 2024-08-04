package com.nocom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Nocom implements ModInitializer {
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, ChunkPos> playerLastChunkMap = new HashMap<>();
	private final List<JsonObject> chunkVisitEntries = new ArrayList<>();
	private final Set<String> visitedChunks = new HashSet<>();
	private WebSocketClient webSocketClient;
	private static final Logger LOGGER = Logger.getLogger(Nocom.class.getName());

	@Override
	public void onInitialize() {
		try {
			URI uri = new URI("ws://localhost:8765");  // Change to your WebSocket server URL
			webSocketClient = new WebSocketClient(uri) {
				@Override
				public void onOpen(ServerHandshake handshake) {
					LOGGER.info("WebSocket connected");
				}

				@Override
				public void onMessage(String message) {
					// No need to handle incoming messages for this use case
				}

				@Override
				public void onClose(int code, String reason, boolean remote) {
					LOGGER.info("WebSocket closed: " + reason);
					retryConnection();
				}

				@Override
				public void onError(Exception ex) {
					LOGGER.log(Level.SEVERE, "WebSocket error", ex);
					retryConnection();
				}
			};
			webSocketClient.connect();
		} catch (URISyntaxException e) {
			LOGGER.log(Level.SEVERE, "Invalid WebSocket URI", e);
		}

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			server.getPlayerManager().getPlayerList().forEach(this::logPlayerChunk);
			writeLogToFile();
		});
	}

	private void retryConnection() {
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				if (webSocketClient != null && !webSocketClient.isOpen()) {
					try {
						LOGGER.info("Attempting to reconnect WebSocket...");
						webSocketClient.reconnect();
					} catch (Exception e) {
						LOGGER.log(Level.SEVERE, "Failed to reconnect WebSocket", e);
					}
				}
			}
		}, 5000);  // Retry connection after 5 seconds
	}

	private void logPlayerChunk(ServerPlayerEntity player) {
		ChunkPos currentChunkPos = player.getChunkPos();
		String playerName = player.getName().getString();
		String chunkKey = currentChunkPos.x + "," + currentChunkPos.z + "," + playerName;

		ChunkPos lastChunkPos = playerLastChunkMap.get(playerName);
		if (lastChunkPos == null || !lastChunkPos.equals(currentChunkPos)) {
			// Update the last chunk position for the player
			playerLastChunkMap.put(playerName, currentChunkPos);

			// Create a JSON object for the log entry
			JsonObject chunkVisited = new JsonObject();
			chunkVisited.addProperty("chunk", currentChunkPos.x + ", " + currentChunkPos.z);
			chunkVisited.addProperty("player", playerName);

			JsonObject logEntry = new JsonObject();
			logEntry.add("ChunkVisited", chunkVisited);

			// Add log entry to the list
			if (!visitedChunks.contains(chunkKey)) {
				chunkVisitEntries.add(logEntry);
				visitedChunks.add(chunkKey);

				// Send log entry via WebSocket
				if (webSocketClient != null && webSocketClient.isOpen()) {
					try {
						webSocketClient.send(gson.toJson(logEntry));
					} catch (Exception e) {
						LOGGER.log(Level.SEVERE, "Failed to send WebSocket message", e);
					}
				} else {
					LOGGER.warning("WebSocket client is not open. Message not sent.");
				}

				// Print log entry to console
				System.out.println(gson.toJson(logEntry));
			}
		}
	}

	private void writeLogToFile() {
		if (chunkVisitEntries.isEmpty()) {
			return;
		}

		// Create a JSON array and add all log entries
		JsonArray jsonArray = new JsonArray();
		chunkVisitEntries.forEach(jsonArray::add);

		// Create the final JSON object to be written
		JsonObject finalLog = new JsonObject();
		finalLog.add("Chunks", jsonArray);

		// Write the final JSON object to the file
		try (FileWriter writer = new FileWriter("C:\\Users\\creeh\\OneDrive\\Main\\PROGRAMS\\NOCOM\\PythonModule\\chunk_visits.json", false)) {
			writer.write(gson.toJson(finalLog));
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "Failed to write chunk visits to file", e);
		}

		// Clear the set after writing to file
		visitedChunks.clear();
	}
}
