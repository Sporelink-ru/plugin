package ru.sporelink.plugin;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class PayCommand implements CommandExecutor {

    private final SporelinkPlugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();

    public PayCommand(SporelinkPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§cUsage: /pay <player> <amount>");
            return true;
        }

        String targetName = args[0];
        long amount;
        try {
            amount = Long.parseLong(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage("§cAmount must be a positive number.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage("§cPlayer " + targetName + " is not online.");
            return true;
        }

        String fromUuid = player.getUniqueId().toString().replace("-", "");
        String toUuid = target.getUniqueId().toString().replace("-", "");
        String nodeUrl = plugin.getNodeUrl(); // Получаем URL ноды из плагина

        player.sendMessage("§7Sending §e" + amount + " §7coins to " + targetName + "...");

        // Делаем запрос асинхронно
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String body = String.format(
                        "{\"to_node\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"amount\":%d}",
                        nodeUrl, fromUuid, toUuid, amount
                );

                HttpRequest req = HttpRequest.newBuilder(URI.create(nodeUrl + "/transfer"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res.statusCode() == 200 && res.body().contains("\"status\":\"committed\"")) {
                        player.sendMessage("§aSuccess! Sent " + amount + " to " + targetName);
                        target.sendMessage("§aYou received " + amount + " from " + player.getName());
                    } else if (res.body().contains("insufficient balance")) {
                        player.sendMessage("§cInsufficient funds!");
                    } else {
                        player.sendMessage("§cTransfer failed: " + res.body());
                    }
                });

            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> 
                    player.sendMessage("§cNetwork error: " + e.getMessage())
                );
            }
        });

        return true;
    }
}