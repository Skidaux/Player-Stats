package com.skidaux.playerstats;
//Imports
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Location;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PlayerStats extends JavaPlugin {

    private final Map<UUID, Instant> playerJoinTimes = new HashMap<>();
    private final Map<UUID, PlayerData> playerData = new HashMap<>();
    private final Map<UUID, Location> playerLastLocation = new HashMap<>();
    private boolean isSaving = false;
    private ExecutorService threadPool;

    private static class PlayerData {
        String username;
        int playerDeaths;
        int blocksMined;
        int blocksPlaced;
        int mobsKilled;
        int playerKills;
        double blocksWalked; // Change the data type to double for more precise tracking
    }

    @Override
    public void onEnable() {
        try {
            openConnection(); // Implement this method to establish a database connection
            getLogger().info("Connected to the database successfully!");
            setupDatabase(); // Implement this method to set up your database tables
            getServer().getPluginManager().registerEvents(new PlayerStatsListener(this), this);

            // Create a thread pool with 4 threads
            threadPool = Executors.newFixedThreadPool(4);

            // Schedule the data-saving task to run every 30 seconds
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveBatchedData, 24_000L, 24_000L);
        } catch (SQLException | ClassNotFoundException ex) {
            getLogger().severe("Exception: " + ex.getMessage());
            getLogger().severe("Could not establish a MySQL connection. Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // Save data one last time before the plugin is disabled
        saveBatchedData();

        // Shutdown the thread pool
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            getLogger().severe("Thread pool termination interrupted: " + ex.getMessage());
        }

        for (Player player : getServer().getOnlinePlayers()) {
            try {
                recordTimePlayed(player.getUniqueId());
                recordLogoutTime(player.getUniqueId());
            } catch (SQLException | ClassNotFoundException e) {
                getLogger().severe("Exception: " + e.getMessage());
            }
        }
    }

    private void recordTimePlayed(UUID uniqueId) {
    }

    //Custom command
    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("db-save")) {
            if (sender.isOp()) {
                saveBatchedData();
                sender.sendMessage("Data saved to the database!");
            } else sender.sendMessage("You must be an operator to execute this command.");

            return true;
        }
        return false;
    }

    private Connection openConnection() throws SQLException, ClassNotFoundException {
        synchronized (this) {
            //DB connection
            Class.forName("com.mysql.cj.jdbc.Driver");
            String password = "password";
            String username = "admin";
            String database = "minecraft";
            int port = 3306;
            String host = "localhost";
            return DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, username, password);
        }
    }

    private void logToFile(String message) {
        File dataFolder = this.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdir()) {
            getLogger().warning("Failed to create data folder.");
            return;
        }

        File saveTo = new File(getDataFolder(), "database-log.txt");
        try {
            if (!saveTo.exists() && !saveTo.createNewFile()) {
                getLogger().warning("Failed to create log file.");
                return;
            }

            try (FileWriter fw = new FileWriter(saveTo, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(message);
            }
        } catch (IOException e) {
            getLogger().severe("Exception: " + e.getMessage());
        }
    }
//SQL table creation
    private void setupDatabase() throws SQLException, ClassNotFoundException {
        try (Connection conn = openConnection()) {
            String createTable = "CREATE TABLE IF NOT EXISTS player_stats (" +
                    "uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                    "username VARCHAR(16) NOT NULL," +
                    "first_join TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "last_logout TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "time_played BIGINT NOT NULL DEFAULT 0," +
                    "mobs_killed INT NOT NULL DEFAULT 0," +
                    "player_deaths INT NOT NULL DEFAULT 0," +
                    "player_kills INT NOT NULL DEFAULT 0," +
                    "blocks_mined INT NOT NULL DEFAULT 0," +
                    "blocks_placed INT NOT NULL DEFAULT 0," +
                    "blocks_walked DOUBLE NOT NULL DEFAULT 0" + 
                    ");";
            conn.createStatement().executeUpdate(createTable);
        }
    }

    public void recordStat(UUID playerUUID, String username, String statColumn, int value) throws SQLException, ClassNotFoundException {
        try (Connection connection = openConnection()) {
            if (statColumn.equals("first_join")) {
                // Handle first_join separately with a valid TIMESTAMP value
                String query = "INSERT INTO player_stats (uuid, username, first_join) " +
                        "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE first_join = CURRENT_TIMESTAMP;";
                PreparedStatement stmt = connection.prepareStatement(query);
                stmt.setString(1, playerUUID.toString());
                stmt.setString(2, username);
                stmt.executeUpdate();
            } else {
                // Handle other columns as usual
                String query = "INSERT INTO player_stats (uuid, username, " + statColumn + ") " +
                        "VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " + statColumn + " = " + statColumn + " + ?;";
                PreparedStatement stmt = connection.prepareStatement(query);
                stmt.setString(1, playerUUID.toString());
                stmt.setString(2, username);
                stmt.setInt(3, value);
                stmt.setInt(4, value);
                stmt.executeUpdate();
            }
        }
    }





    private void addNewPlayerToDatabase(UUID playerUUID, String name) {
    }

    public void recordLogoutTime(UUID playerUUID) throws SQLException, ClassNotFoundException {
        try (Connection connection = openConnection()) {
            String query = "UPDATE player_stats SET last_logout = CURRENT_TIMESTAMP WHERE uuid = ?;";
            PreparedStatement stmt = connection.prepareStatement(query);
            stmt.setString(1, playerUUID.toString());
            stmt.executeUpdate();
        }
    }

    private void saveBatchedData() {
        if (threadPool != null) {
            isSaving = true;
            // Submit a data-saving task to the thread pool
            Future<?> future = threadPool.submit(() -> {
                synchronized (playerData) {
                    for (Map.Entry<UUID, PlayerData> entry : playerData.entrySet()) {
                        UUID playerUUID = entry.getKey();
                        PlayerData data = entry.getValue();

                        try {
                            recordStat(playerUUID, data.username, "player_deaths", data.playerDeaths);
                            recordStat(playerUUID, data.username, "blocks_mined", data.blocksMined);
                            recordStat(playerUUID, data.username, "blocks_placed", data.blocksPlaced);
                            recordStat(playerUUID, data.username, "blocks_walked", (int) data.blocksWalked); // Cast to int
                            recordStat(playerUUID, data.username, "mobs_killed", data.mobsKilled);
                            recordStat(playerUUID, data.username, "player_kills", data.playerKills);
                        } catch (SQLException | ClassNotFoundException e) {
                            getLogger().severe("Exception: " + e.getMessage());
                        }
                    }

                    playerData.clear();
                    isSaving = false;
                }
            });
        }
    }
    //Event listeners
    public class PlayerStatsListener implements Listener {
        private final PlayerStats plugin;

        public PlayerStatsListener(PlayerStats plugin) {
            this.plugin = plugin;
        }


        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();

            int maxRetries = 3;
            int retries = 0;

            while (retries < maxRetries) {
                try {
                    if (!playerJoinTimes.containsKey(playerUUID)) {
                        playerJoinTimes.put(playerUUID, Instant.now());
                        // Record time played and other relevant stats
                        recordTimePlayed(playerUUID);
                        recordStat(playerUUID, player.getName(), "first_join", 1);

                        // Add a new player to the database when they join
                        addNewPlayerToDatabase(playerUUID, player.getName());

                        // Break out of the loop if successful
                        break;
                    }
                } catch (SQLException | ClassNotFoundException e) {
                    getLogger().severe("Exception: " + e.getMessage());

                    // Increment the retry count and log a warning
                    retries++;
                    getLogger().warning("Retrying attempt " + retries + " to record time played for " + player.getName());
                }
            }
        }



        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            UUID playerUUID = event.getPlayer().getUniqueId();

            // Check if the player has a join time recorded
            if (playerJoinTimes.containsKey(playerUUID)) {
                try {
                    recordTimePlayed(playerUUID);
                    recordLogoutTime(playerUUID);
                    // Update the last logout time when players quit
                } catch (SQLException | ClassNotFoundException e) {
                    getLogger().severe("Exception: " + e.getMessage());
                }

                // Remove the player's join time
                playerJoinTimes.remove(playerUUID);
            } else {
                getLogger().warning("No join time found for: " + playerUUID);
            }
        }


        public void recordTimePlayed(UUID playerUUID) throws SQLException, ClassNotFoundException {
            Instant joinTime = playerJoinTimes.get(playerUUID);
            if (joinTime != null) {
                long secondsPlayed = Instant.now().getEpochSecond() - joinTime.getEpochSecond();
                getLogger().info("Time played by " + playerUUID + ": " + secondsPlayed + " seconds");
                try (Connection connection = openConnection()) {
                    String query = "UPDATE player_stats SET time_played = time_played + ? WHERE uuid = ?;";
                    PreparedStatement stmt = connection.prepareStatement(query);
                    stmt.setLong(1, secondsPlayed);
                    stmt.setString(2, playerUUID.toString());
                    stmt.executeUpdate();
                }
            } else {
                getLogger().warning("No join time found for: " + playerUUID);
            }
        }



        @EventHandler
        public void onPlayerDeath(PlayerDeathEvent event) {
            UUID playerUUID = event.getEntity().getUniqueId();
            synchronized (plugin.playerData) {
                PlayerData data = plugin.playerData.computeIfAbsent(playerUUID, k -> new PlayerData());
                data.playerDeaths++;
                data.username = event.getEntity().getName();
            }
        }

        @EventHandler
        public void onBlockBreak(BlockBreakEvent event) {
            UUID playerUUID = event.getPlayer().getUniqueId();
            synchronized (plugin.playerData) {
                PlayerData data = plugin.playerData.computeIfAbsent(playerUUID, k -> new PlayerData());
                data.blocksMined++;
                data.username = event.getPlayer().getName();
            }
        }

        @EventHandler
        public void onBlockPlace(BlockPlaceEvent event) {
            UUID playerUUID = event.getPlayer().getUniqueId();
            synchronized (plugin.playerData) {
                PlayerData data = plugin.playerData.computeIfAbsent(playerUUID, k -> new PlayerData());
                data.blocksPlaced++;
                data.username = event.getPlayer().getName();
            }
        }

        @EventHandler
        public void onPlayerMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();
            Location from = event.getFrom();
            Location to = event.getTo();

            if (!from.getWorld().equals(to.getWorld())) {
                // Player moved to a different world, do nothing or handle it as needed
                return;
            }

            double horizontalDistance = Math.sqrt(
                    Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2)
            );

            synchronized (plugin.playerData) {
                PlayerData data = plugin.playerData.computeIfAbsent(playerUUID, k -> new PlayerData());

                // Only accumulate horizontal distance as blocks walked
                data.blocksWalked += horizontalDistance;
                data.username = player.getName();
            }

            plugin.playerLastLocation.put(playerUUID, to);
        }



        @EventHandler
        public void onEntityDeath(EntityDeathEvent event) {
            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                UUID playerUUID = killer.getUniqueId();
                synchronized (plugin.playerData) {
                    PlayerData data = plugin.playerData.computeIfAbsent(playerUUID, k -> new PlayerData());
                    if (event.getEntity() instanceof Player) {
                        data.playerKills++;
                    } else {
                        data.mobsKilled++;
                    }
                    data.username = killer.getName();
                }
            }
        }
    }
}
