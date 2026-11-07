package ru.sporelink.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class SporelinkPlugin extends JavaPlugin implements Listener {

    private String hubUrl;
    private String nodeUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hubUrl = getConfig().getString("hub-url", "http://localhost:5000");
        nodeUrl = getConfig().getString("node-url", "http://localhost:8080");
        getServer().getPluginManager().registerEvents(this, this);
        
        // Регистрируем команду /pay
        getCommand("pay").setExecutor(new PayCommand(this));
        
        getLogger().info("Sporelink Plugin enabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString().replace("-", "");

        // Делаем запросы асинхронно, чтобы не вешать основной поток сервера
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            long balance = fetchBalance(uuid);
            int reputation = fetchReputation(uuid);

            // Возвращаемся в основной поток, чтобы отправить сообщение игроку
            getServer().getScheduler().runTask(this, () -> {
                player.sendMessage("§b[Sporelink] §fДобро пожаловать, §e" + player.getName() + "§f!");
                player.sendMessage("§b[Sporelink] §fБаланс: §a" + balance + " §f| Репутация: §d" + reputation);
            });
        });
    }

    private long fetchBalance(String uuid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(nodeUrl + "/balance/" + uuid)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            return json.get("balance").getAsLong();
        } catch (Exception e) {
            getLogger().warning("Failed to fetch balance: " + e.getMessage());
            return 0;
        }
    }

    private int fetchReputation(String uuid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(hubUrl + "/reputation/" + uuid)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            return json.get("reputation").getAsInt();
        } catch (Exception e) {
            getLogger().warning("Failed to fetch reputation: " + e.getMessage());
            return 0;
        }
    }

    public String getNodeUrl() {
        return nodeUrl;
    }
}